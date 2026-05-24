// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.QuantaAISessionState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SessionMemoryService(
    private val project: Project,
) {
    companion object {
        private const val MAX_BRIEF_CHARS = 2_500
        private const val MAX_SECTION_ITEMS = 12
        private const val MAX_LOG_ITEMS = 20
        private const val COMPACTION_TAIL_MESSAGES = 6
    }

    private val log = Logger.getInstance(SessionMemoryService::class.java)
    private val mapper = ObjectMapper()
    private val lock = Any()
    private val keyResolver = MainConversationKeyResolver(project)

    fun ensureInitialized() {
        synchronized(lock) {
            persistFacts(loadFactsUnsafe())
        }
    }

    fun loadBrief(maxChars: Int = 8_000): String {
        ensureInitialized()
        return renderBriefMarkdown(loadFactsUnsafe()).truncate(maxChars)
    }

    fun loadDetailed(maxChars: Int = 16_000): String {
        ensureInitialized()
        return renderSessionMarkdown(loadFactsUnsafe()).truncate(maxChars)
    }

    fun refreshFromCurrentState(
        reason: String,
        explicitNote: String? = null,
        userText: String? = null,
        assistantText: String? = null,
        force: Boolean = false,
    ) {
        synchronized(lock) {
            val facts = loadFactsUnsafe()
            mergeCurrentState(facts, reason, explicitNote, userText, assistantText, force)
            persistFacts(facts)
        }
    }

    fun pinFact(
        fact: String,
        supersedes: String? = null,
    ) {
        val clean = fact.trim()
        if (clean.isBlank()) return
        synchronized(lock) {
            val facts = loadFactsUnsafe()
            supersedeFactUnsafe(facts, supersedes)
            addUniqueFront(facts.verified_facts, clean)
            addUniqueFront(facts.current_state, "Pinned fact: $clean")
            persistFacts(facts)
        }
    }

    fun markRootCause(
        fact: String,
        supersedes: String? = null,
    ) {
        val clean = fact.trim()
        if (clean.isBlank()) return
        synchronized(lock) {
            val facts = loadFactsUnsafe()
            supersedeFactUnsafe(facts, supersedes)
            addUniqueFront(facts.root_causes, clean)
            addUniqueFront(facts.current_state, "Root cause confirmed: $clean")
            persistFacts(facts)
        }
    }

    fun recordToolEvent(
        toolName: String,
        argsJson: String?,
        result: Any?,
    ) {
        synchronized(lock) {
            val facts = loadFactsUnsafe()
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
            persistFacts(facts)
        }
    }

    fun compactConversationHistory(): String {
        refreshFromCurrentState(reason = "compact_with_memory", force = true)
        val key = conversationKeyForMain()
        val brief = loadBrief(maxChars = MAX_BRIEF_CHARS)
        if (brief.isBlank()) return ""
        try {
            val existingMessages =
                QuantaAISessionState.instance.state.conversations[key]
                    .orEmpty()
            val tail = existingMessages.takeLast(COMPACTION_TAIL_MESSAGES)
            QuantaAISessionState.instance.state.conversations[key] =
                mutableListOf(
                    QuantaAISessionState.PersistedMessage(
                        System.currentTimeMillis(),
                        "system",
                        "Session memory brief (restored from structured session facts):\n$brief",
                        null,
                    ),
                ).apply {
                    addAll(tail)
                }
            QuantaAISessionState.instance.state.conversationSummaries[key] = brief
        } catch (t: Throwable) {
            QDLog.warn(log, { "Failed to compact conversation with session memory: ${t.message}" }, t)
        }
        return brief
    }

    private fun mergeCurrentState(
        facts: SessionMemoryFacts,
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

    private fun mergePlanState(facts: SessionMemoryFacts) {
        val plan =
            try {
                project.service<SessionPlanService>().loadPlanSnapshot()
            } catch (_: Throwable) {
                SessionPlan()
            }
        if (!plan.hasMeaningfulContent()) return
        addUniqueFront(facts.current_state, "Plan ${plan.normalizedStatus()}.")
        if (facts.goal.isBlank() && plan.goal.isNotBlank()) {
            facts.goal = plan.goal
        }
        plan.uncheckedTaskTexts().take(5).forEach {
            addUniqueFront(facts.next_steps, it)
        }
    }

    private fun mergeConversationState(
        facts: SessionMemoryFacts,
        userText: String?,
        assistantText: String?,
    ) {
        val key = conversationKeyForMain()
        val messages =
            try {
                QuantaAISessionState.instance.state.conversations[key]
                    .orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
        val recentUsers =
            messages
                .asReversed()
                .filter { it.role == "user" }
                .take(3)
                .mapNotNull { it.text?.trim()?.takeIf(String::isNotBlank) }
        val recentAssistant =
            messages
                .asReversed()
                .firstOrNull { it.role == "assistant" }
                ?.text
                ?.trim()
                .orEmpty()
        userText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { addUniqueFront(facts.current_state, "User request: ${singleLine(it, 220)}") }
        assistantText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { addUniqueFront(facts.current_state, "Latest assistant answer: ${singleLine(it, 240)}") }
        recentUsers.forEach { addUniqueFront(facts.current_state, "Recent user input: ${singleLine(it, 220)}") }
        recentAssistant.takeIf { it.isNotBlank() }?.let {
            addUniqueFront(facts.current_state, "Recent assistant output: ${singleLine(it, 240)}")
        }
    }

    private fun inferGoalFromConversation(): String {
        val key = conversationKeyForMain()
        val messages =
            try {
                QuantaAISessionState.instance.state.conversations[key]
                    .orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
        return messages
            .asReversed()
            .firstOrNull { it.role == "user" }
            ?.text
            ?.trim()
            ?.take(220)
            .orEmpty()
    }

    private fun persistFacts(facts: SessionMemoryFacts) {
        trimFacts(facts)
        val key = conversationKeyForMain()
        try {
            QuantaAISessionState.instance.state.sessionMemories[key] = facts.deepCopy()
            QuantaAISessionState.instance.state.conversationSummaries[key] = renderBriefMarkdown(facts)
        } catch (t: Throwable) {
            QDLog.warn(log, { "Failed to persist session memory facts: ${t.message}" }, t)
        }
    }

    private fun renderSessionMarkdown(facts: SessionMemoryFacts): String =
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
                if (facts.files_changed_notes.isNotEmpty()) facts.files_changed_notes else facts.changed_files,
            )
            appendSection("Important commands and logs", facts.important_commands + facts.important_logs)
            appendSection("Open questions", facts.open_questions)
            appendSection("Next steps", facts.next_steps)
            appendSection("Superseded facts", facts.superseded_facts)
        }.trim() + "\n"

    private fun renderBriefMarkdown(facts: SessionMemoryFacts): String {
        val out =
            buildString {
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
        return out.truncate(MAX_BRIEF_CHARS)
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

    private fun trimFacts(facts: SessionMemoryFacts) {
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
        this
            .map { it.trim() }
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
        list.removeAll { it.trim() == clean }
        list.add(0, clean)
    }

    private fun supersedeFactUnsafe(
        facts: SessionMemoryFacts,
        supersedes: String?,
    ) {
        supersedes
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { addUniqueFront(facts.superseded_facts, it) }
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
            "RunGradleBuildTool" -> {
                "./gradlew ${
                    args["tasks"]?.toString()?.ifBlank { "compileKotlin compileJava" } ?: "compileKotlin compileJava"
                }"
            }

            "RunGradleTestsTool" -> {
                "./gradlew ${args["tasks"]?.toString()?.ifBlank { "test" } ?: "test"}"
            }

            "GradleSyncTool" -> {
                "Gradle sync${
                    args["projectPath"]?.toString()?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                }"
            }

            "GetTestInfoTool" -> {
                "Read test report for ${args["testClass"] ?: "unknown"}.${args["testName"] ?: "unknown"}"
            }

            else -> {
                ""
            }
        }

    private fun summarizeResult(result: Any?): String {
        val raw =
            when (result) {
                null -> {
                    ""
                }

                is String -> {
                    result
                }

                is Map<*, *> -> {
                    try {
                        mapper.writeValueAsString(result)
                    } catch (_: Throwable) {
                        result.toString()
                    }
                }

                else -> {
                    result.toString()
                }
            }
        return singleLine(raw, 320)
    }

    private fun singleLine(
        text: String,
        maxChars: Int,
    ): String =
        text
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)

    private fun loadFactsUnsafe(): SessionMemoryFacts =
        try {
            QuantaAISessionState.instance.state.sessionMemories[conversationKeyForMain()]
                ?.deepCopy()
                ?: SessionMemoryFacts()
        } catch (t: Throwable) {
            QDLog.warn(log, { "Failed to load session memory facts: ${t.message}" }, t)
            SessionMemoryFacts()
        }

    private fun SessionMemoryFacts.deepCopy(): SessionMemoryFacts =
        SessionMemoryFacts(
            goal = goal,
            root_causes = root_causes.toMutableList(),
            decisions = decisions.toMutableList(),
            verified_facts = verified_facts.toMutableList(),
            open_questions = open_questions.toMutableList(),
            next_steps = next_steps.toMutableList(),
            changed_files = changed_files.toMutableList(),
            important_commands = important_commands.toMutableList(),
            important_logs = important_logs.toMutableList(),
            superseded_facts = superseded_facts.toMutableList(),
            current_state = current_state.toMutableList(),
            rejected_hypotheses = rejected_hypotheses.toMutableList(),
            environment_details = environment_details.toMutableList(),
            service_endpoints = service_endpoints.toMutableList(),
            files_changed_notes = files_changed_notes.toMutableList(),
        )

    private fun String.truncate(maxChars: Int): String = if (length <= maxChars) this else take(maxChars) + "\n... (truncated)"

    private fun conversationKeyForMain(): String = keyResolver.conversationKeyForMain()
}
