// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Resolves the branch-aware conversation key used for the main chat/session thread.
 *
 * This centralizes the fallback logic so OpenAI/session/memory services do not duplicate Git branch
 * probing and key formatting behavior.
 */
class MainConversationKeyResolver(
    private val project: Project,
) {
    fun conversationKeyForMain(): String {
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
