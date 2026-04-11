// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.tools.PathUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

@Service(Service.Level.PROJECT)
class SessionMemoryService(
    private val project: Project,
) {
    data class SessionFacts(
        var goal: String = "",
        var root_causes: MutableList<String> = mutableListOf(),
        var decisions: MutableList<String> = mutableListOf(),
        var verified_facts: MutableList<String> = mutableListOf(),
        var open_questions: MutableList<String> = mutableListOf(),
        var next_steps: MutableList<String> = mutableListOf(),
        var changed_files: MutableList<String> = mutableListOf(),
        var important_commands: MutableList<String> = mutableListOf(),
        var important_logs: MutableList<String> = mutableListOf(),
        var superseded_facts: MutableList<String> = mutableListOf(),
        var current_state: MutableList<String> = mutableListOf(),
        var rejected_hypotheses: MutableList<String> = mutableListOf(),
        var environment_details: MutableList<String> = mutableListOf(),
        var service_endpoints: MutableList<String> = mutableListOf(),
        var files_changed_notes: MutableList<String> = mutableListOf(),
    )

    companion object {
        private const val DIR = ".quantadance/session"
        private const val SESSION_FILE = "session.md"
        private const val BRIEF_FILE = "session-brief.md"
        private const val FACTS_FILE = "facts.json"
        private const val MAX_BRIEF_CHARS = 2_500
        private const val MAX_SECTION_ITEMS = 12
        private const val MAX_LOG_ITEMS = 20
    }

    private val log = Logger.getInstance(SessionMemoryService::class.java)
    private val mapper = ObjectMapper()
    private val lock = Any()

    fun ensureInitialized() {
        synchronized(lock) {
            val dir = sessionDirIo() ?: return
            if (!dir.exists()) dir.mkdirs()
            val facts = loadFactsUnsafe(dir)
            writeFactsUnsafe(dir, facts)
            writeTextIfMissing(File(dir, SESSION_FILE), renderSessionMarkdown(facts))
            writeTextIfMissing(File(dir, BRIEF_FILE), renderBriefMarkdown(facts))
            refreshVfs(dir)
        }
    }

    fun loadBrief(maxChars: Int = 8_000): String {
        ensureInitialized()
        val file = sessionFileIo(BRIEF_FILE) ?: return ""
        return readTextLimited(file, maxChars)
    }

    fun loadDetailed(maxChars: Int = 16_000): String {
        ensureInitialized()
        val file = sessionFileIo(SESSION_FILE) ?: return ""
        return readTextLimited(file, maxChars)
    }

    fun refreshFromCurrentState(
        reason: String,
        explicitNote: String? = null,
        userText: String? = null,
        assistantText: String? = null,
        force: Boolean = false,
    ) {
        synchronized(lock) {
            val dir = sessionDirIo() ?: return
            if (!dir.exists()) dir.mkdirs()
            val facts = loadFactsUnsafe(dir)
            mergeCurrentState(facts, reason, explicitNote, userText, assistantText, force)
            writeAllUnsafe(dir, facts)
        }
    }

    fun pinFact(
        fact: String,
        supersedes: String? = null,
    ) {
        val clean = fact.trim()
        if (clean.isBlank()) return
        synchronized(lock) {
            val dir = sessionDirIo() ?: return
            if (!dir.exists()) dir.mkdirs()
            val facts = loadFactsUnsafe(dir)
            supersedeFactUnsafe(facts, supersedes)
            addUniqueFront(facts.verified_facts, clean)
            addUniqueFront(facts.current_state, "Pinned fact: $clean")
            writeAllUnsafe(dir, facts)
        }
    }

    fun markRootCause(
        fact: String,
        supersedes: String? = null,
    ) {
        val clean = fact.trim()
        if (clean.isBlank()) return
        synchronized(lock) {
            val dir = sessionDirIo() ?: return
            if (!dir.exists()) dir.mkdirs()
            val facts = loadFactsUnsafe(dir)
            supersedeFactUnsafe(facts, supersedes)
            addUniqueFront(facts.root_causes, clean)
            addUniqueFront(facts.current_state, "Root cause confirmed: $clean")
            writeAllUnsafe(dir, facts)
        }
    }

    fun recordToolEvent(
        toolName: String,
        argsJson: String?,
        result: Any?,
    ) {
        synchronized(lock) {
            val dir = sessionDirIo() ?: return
            if (!dir.exists()) dir.mkdirs()
            val facts = loadFactsUnsafe(dir)
            val args = parseArgs(argsJson)
            val resultText = summarizeResult(result)
            when (toolName) {
                "PatchFile", "CreateOrUpdateFile", "DeleteFileTool", "CopyFileOrDirectoryTool" -> {
                    extractPaths(args).forEach { addUniqueFront(facts.changed_files, it) }
                    addUniqueFront(facts.current_state, "Updated project files via $toolName.")
                    if (resultText.isNotBlank()) addUniqueFront(facts.files_changed_notes, "$toolName: $resultText")
                }

                "RunGradleBuildTool", "RunGradleTestsTool", "GradleSyncTool", "GetTestInfoTool" -> {
                    val cmd = buildCommand(toolName, args)
                    if (cmd.isNotBlank()) addUniqueFront(facts.important_commands, cmd)
                    if (resultText.isNotBlank()) addUniqueFront(facts.important_logs, "$toolName: $resultText")
                    addUniqueFront(facts.current_state, "Build/test tooling ran via $toolName.")
                }

                "SessionPlanTool" -> {
                    addUniqueFront(facts.decisions, "Session plan updated (${args["action"] ?: "READ"}).")
                }
            }
            writeAllUnsafe(dir, facts)
        }
    }

    fun compactConversationHistory(): String {
        refreshFromCurrentState(reason = "compact_with_memory", force = true)
        val key = conversationKeyForMain()
        val brief = loadBrief(maxChars = MAX_BRIEF_CHARS)
        if (brief.isBlank()) return ""
        try {
            QuantaAISettingsState.instance.state.conversations[key] =
                mutableListOf(
                    QuantaAISettingsState.PersistedMessage(
                        System.currentTimeMillis(),
                        "system",
                        "Session memory brief (restored from disk):\n$brief",
                        null,
                    ),
                )
            QuantaAISettingsState.instance.state.conversationSummaries[key] = brief
        } catch (t: Throwable) {
            QDLog.warn(log, { "Failed to compact conversation with session memory: ${t.message}" }, t)
        }
        return brief
    }

    private fun mergeCurrentState(
        facts: SessionFacts,
        reason: String,
        explicitNote: String?,
        userText: String?,
        assistantText: String?,
        force: Boolean,
    ) {
        if (facts.goal.isBlank()) {
            facts.goal = inferGoalFromConversation().ifBlank { explicitNote?.trim().orEmpty() }
        }
        mergePlanState(facts)
        mergeConversationState(facts, userText, assistantText)
        explicitNote?.trim()?.takeIf { it.isNotBlank() }?.let { addUniqueFront(facts.current_state, it) }
        if (force) addUniqueFront(facts.current_state, "Session memory refreshed before context compaction.")
        addUniqueFront(facts.current_state, "Last memory refresh reason: $reason")
        trimFacts(facts)
    }

    private fun mergePlanState(facts: SessionFacts) {
        val planText = try {
            SessionPlanService(project).loadText(maxChars = 8_000)
        } catch (_: Throwable) {
            ""
        }
        if (planText.isBlank()) return
        val lines = planText.lines().map { it.trim() }
        val status = lines.firstOrNull { it.startsWith("Status:", ignoreCase = true) }
        status?.let { addUniqueFront(facts.current_state, "Plan ${it.removePrefix("Status:").trim()}.") }

        collectSectionValue(lines, "Goal:")?.let {
            if (facts.goal.isBlank()) facts.goal = it
        }

        lines.filter { it.startsWith("- [ ] ") }.take(5).forEach {
            addUniqueFront(facts.next_steps, it.removePrefix("- [ ] ").trim())
        }
    }

    private fun mergeConversationState(
        facts: SessionFacts,
        userText: String?,
        assistantText: String?,
    ) {
        val key = conversationKeyForMain()
        val messages = try {
            QuantaAISettingsState.instance.state.conversations[key].orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        val recentUsers = messages.asReversed().filter { it.role == "user" }.take(3)
            .mapNotNull { it.text?.trim()?.takeIf(String::isNotBlank) }
        val recentAssistant = messages.asReversed().firstOrNull { it.role == "assistant" }?.text?.trim().orEmpty()
        userText?.trim()?.takeIf { it.isNotBlank() }
            ?.let { addUniqueFront(facts.current_state, "User request: ${singleLine(it, 220)}") }
        assistantText?.trim()?.takeIf { it.isNotBlank() }
            ?.let { addUniqueFront(facts.current_state, "Assistant update: ${singleLine(it, 220)}") }

        recentUsers.firstOrNull()?.let {
            if (facts.goal.isBlank()) facts.goal = singleLine(it, 220)
        }
        recentUsers.take(2).forEach {
            addUniqueFront(facts.current_state, "Recent user focus: ${singleLine(it, 220)}")
        }
        if (recentAssistant.isNotBlank()) {
            addUniqueFront(facts.current_state, "Latest assistant response: ${singleLine(recentAssistant, 220)}")
        }

        val rollingSummary = try {
            QuantaAISettingsState.instance.state.conversationSummaries[key].orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (rollingSummary.isNotBlank()) {
            addUniqueFront(facts.current_state, "Rolling summary: ${singleLine(rollingSummary, 220)}")
        }
    }

    private fun inferGoalFromConversation(): String {
        val key = conversationKeyForMain()
        val messages = try {
            QuantaAISettingsState.instance.state.conversations[key].orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        return messages.asReversed().firstOrNull { it.role == "user" }?.text?.trim()?.take(220).orEmpty()
    }

    private fun writeAllUnsafe(
        dir: File,
        facts: SessionFacts,
    ) {
        trimFacts(facts)
        val sessionText = renderSessionMarkdown(facts)
        val briefText = renderBriefMarkdown(facts)
        writeFactsUnsafe(dir, facts)
        writeText(File(dir, SESSION_FILE), sessionText)
        writeText(File(dir, BRIEF_FILE), briefText)
        try {
            QuantaAISettingsState.instance.state.conversationSummaries[conversationKeyForMain()] = briefText
        } catch (_: Throwable) {
        }
        refreshVfs(dir)
    }

    private fun renderSessionMarkdown(facts: SessionFacts): String =
        buildString {
            appendLine("# Session Memory")
            appendSection("Goal", facts.goal.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList())
            appendSection("Decisions made", facts.decisions)
            appendSection("Current state", facts.current_state)
            appendSection("Verified facts", facts.verified_facts)
            appendSection("Root cause findings", facts.root_causes)
            appendSection("Rejected hypotheses / dead ends", facts.rejected_hypotheses)
            appendSection("Important environment/runtime details", facts.environment_details)
            appendSection("Service endpoints, ports, DNS, cert assumptions", facts.service_endpoints)
            appendSection(
                "Files changed",
                if (facts.files_changed_notes.isNotEmpty()) facts.files_changed_notes else facts.changed_files
            )
            appendSection("Important commands and logs", facts.important_commands + facts.important_logs)
            appendSection("Open questions", facts.open_questions)
            appendSection("Next steps", facts.next_steps)
            appendSection("Superseded facts", facts.superseded_facts)
        }.trim() + "\n"

    private fun renderBriefMarkdown(facts: SessionFacts): String {
        val out = buildString {
            appendLine("# Session Brief")
            appendSection("Goal", facts.goal.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList())
            appendSection("Decisions made", facts.decisions.take(4))
            appendSection("Current state", facts.current_state.take(6))
            appendSection("Verified facts", facts.verified_facts.take(5))
            appendSection("Root cause findings", facts.root_causes.take(4))
            appendSection("Rejected hypotheses / dead ends", facts.rejected_hypotheses.take(3))
            appendSection("Important environment/runtime details", facts.environment_details.take(4))
            appendSection("Service endpoints, ports, DNS, cert assumptions", facts.service_endpoints.take(4))
            appendSection("Files changed", facts.changed_files.take(6))
            appendSection("Important commands and logs", (facts.important_commands + facts.important_logs).take(6))
            appendSection("Open questions", facts.open_questions.take(4))
            appendSection("Next steps", facts.next_steps.take(5))
        }.trim() + "\n"
        return if (out.length <= MAX_BRIEF_CHARS) out else out.take(MAX_BRIEF_CHARS) + "\n... (truncated)"
    }

    private fun StringBuilder.appendSection(
        title: String,
        items: List<String>,
    ) {
        appendLine("## $title")
        if (items.isEmpty()) {
            appendLine("- None recorded")
        } else {
            items.filter { it.isNotBlank() }.forEach { appendLine("- ${it.trim()}") }
        }
        appendLine()
    }

    private fun trimFacts(facts: SessionFacts) {
        facts.root_causes = facts.root_causes.distinctTrimmed(MAX_SECTION_ITEMS)
        facts.decisions = facts.decisions.distinctTrimmed(MAX_SECTION_ITEMS)
        facts.verified_facts = facts.verified_facts.distinctTrimmed(MAX_SECTION_ITEMS)
        facts.open_questions = facts.open_questions.distinctTrimmed(MAX_SECTION_ITEMS)
        facts.next_steps = facts.next_steps.distinctTrimmed(MAX_SECTION_ITEMS)
        facts.changed_files = facts.changed_files.distinctTrimmed(MAX_SECTION_ITEMS)
        facts.important_commands = facts.important_commands.distinctTrimmed(MAX_LOG_ITEMS)
        facts.important_logs = facts.important_logs.distinctTrimmed(MAX_LOG_ITEMS)
        facts.superseded_facts = facts.superseded_facts.distinctTrimmed(MAX_SECTION_ITEMS)
        facts.current_state = facts.current_state.distinctTrimmed(MAX_SECTION_ITEMS)
        facts.rejected_hypotheses = facts.rejected_hypotheses.distinctTrimmed(MAX_SECTION_ITEMS)
        facts.environment_details = facts.environment_details.distinctTrimmed(MAX_SECTION_ITEMS)
        facts.service_endpoints = facts.service_endpoints.distinctTrimmed(MAX_SECTION_ITEMS)
        facts.files_changed_notes = facts.files_changed_notes.distinctTrimmed(MAX_SECTION_ITEMS)
        facts.goal = facts.goal.trim().take(300)
    }

    private fun MutableList<String>.distinctTrimmed(limit: Int): MutableList<String> =
        this.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
            .toMutableList()

    private fun addUniqueFront(
        list: MutableList<String>,
        value: String,
    ) {
        val clean = value.trim()
        if (clean.isBlank()) return
        list.removeAll { it.equals(clean, ignoreCase = true) }
        list.add(0, clean)
    }

    private fun supersedeFactUnsafe(
        facts: SessionFacts,
        supersedes: String?,
    ) {
        val clean = supersedes?.trim().orEmpty()
        if (clean.isBlank()) return
        addUniqueFront(facts.superseded_facts, clean)
        listOf(
            facts.verified_facts,
            facts.root_causes,
            facts.decisions,
            facts.open_questions,
            facts.next_steps,
            facts.current_state,
        ).forEach { it.removeAll { item -> item.equals(clean, ignoreCase = true) } }
    }

    private fun parseArgs(argsJson: String?): Map<String, Any?> =
        if (argsJson.isNullOrBlank()) {
            emptyMap()
        } else {
            try {
                @Suppress("UNCHECKED_CAST")
                mapper.readValue(argsJson, Map::class.java) as Map<String, Any?>
            } catch (_: Throwable) {
                emptyMap()
            }
        }

    private fun extractPaths(args: Map<String, Any?>): List<String> =
        listOf("filePath", "sourcePath", "destinationPath", "path")
            .mapNotNull { args[it]?.toString()?.trim() }
            .filter { it.isNotBlank() }

    private fun buildCommand(
        toolName: String,
        args: Map<String, Any?>,
    ): String =
        when (toolName) {
            "RunGradleBuildTool" -> "./gradlew ${
                args["tasks"]?.toString()?.ifBlank { "compileKotlin compileJava" } ?: "compileKotlin compileJava"
            }"

            "RunGradleTestsTool" -> "./gradlew ${args["tasks"]?.toString()?.ifBlank { "test" } ?: "test"}"
            "GradleSyncTool" -> "Gradle sync${
                args["projectPath"]?.toString()?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            }"

            "GetTestInfoTool" -> "Read test report for ${args["testClass"] ?: "unknown"}.${args["testName"] ?: "unknown"}"
            else -> ""
        }

    private fun summarizeResult(result: Any?): String {
        val raw =
            when (result) {
                null -> ""
                is String -> result
                is Map<*, *> -> try {
                    mapper.writeValueAsString(result)
                } catch (_: Throwable) {
                    result.toString()
                }

                else -> result.toString()
            }
        return singleLine(raw, 320)
    }

    private fun singleLine(
        text: String,
        maxChars: Int,
    ): String = text.replace("\n", " ").replace(Regex("\\s+"), " ").trim().take(maxChars)

    private fun collectSectionValue(
        lines: List<String>,
        header: String,
    ): String? {
        val idx = lines.indexOfFirst { it.equals(header, ignoreCase = true) }
        if (idx < 0) return null
        return lines.drop(idx + 1).firstOrNull { it.isNotBlank() }?.removePrefix("- ")?.trim()
    }

    private fun writeFactsUnsafe(
        dir: File,
        facts: SessionFacts,
    ) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(File(dir, FACTS_FILE), facts)
        } catch (t: Throwable) {
            QDLog.warn(log, { "Failed to write facts.json: ${t.message}" }, t)
        }
    }

    private fun loadFactsUnsafe(dir: File): SessionFacts {
        val file = File(dir, FACTS_FILE)
        return try {
            if (file.exists()) mapper.readValue(file, SessionFacts::class.java) else SessionFacts()
        } catch (t: Throwable) {
            QDLog.warn(log, { "Failed to read facts.json: ${t.message}" }, t)
            SessionFacts()
        }
    }

    private fun writeText(
        file: File,
        text: String,
    ) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(text)
        } catch (t: Throwable) {
            QDLog.warn(log, { "Failed to write ${file.name}: ${t.message}" }, t)
        }
    }

    private fun writeTextIfMissing(
        file: File,
        text: String,
    ) {
        if (!file.exists()) writeText(file, text)
    }

    private fun refreshVfs(dir: File) {
        ApplicationManager.getApplication().invokeLater {
            try {
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)?.refresh(false, true)
            } catch (_: Throwable) {
            }
        }
    }

    private fun readTextLimited(
        file: File,
        maxChars: Int,
    ): String {
        return try {
            if (!file.exists()) "" else {
                val text = file.readText()
                if (text.length <= maxChars) text else text.take(maxChars) + "\n... (truncated)"
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun sessionDirIo(): File? {
        val base = PathUtils.projectRootPath(project)
        if (base.isNullOrBlank()) return null
        return File(base, DIR)
    }

    private fun sessionFileIo(name: String): File? = sessionDirIo()?.let { File(it, name) }

    private fun conversationKeyForMain(): String {
        val base = "main"
        val branch =
            try {
                val gitClass = Class.forName("git4idea.repo.GitRepositoryManager")
                val method = gitClass.getMethod("getInstance", Project::class.java)
                val mgr = method.invoke(null, project)
                val reposMethod = gitClass.getMethod("getRepositories")
                val repos = reposMethod.invoke(mgr) as java.util.List<*>
                if (repos.isNotEmpty()) {
                    val repo = repos[0]
                    val branchMethod = repo.javaClass.getMethod("getCurrentBranchName")
                    branchMethod.invoke(repo) as String? ?: "no-branch"
                } else {
                    "no-branch"
                }
            } catch (_: Throwable) {
                try {
                    val basePath = PathUtils.projectRootPath(project)
                    if (basePath != null) {
                        val pb = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                        pb.directory(File(basePath))
                        pb.redirectErrorStream(true)
                        val proc = pb.start()
                        val out = proc.inputStream.bufferedReader().readText().trim()
                        proc.waitFor()
                        if (out.isNotBlank()) out else "no-branch"
                    } else {
                        "no-branch"
                    }
                } catch (_: Throwable) {
                    "no-branch"
                }
            }
        return "$base@${branch.replace(' ', '_')}"
    }
}
