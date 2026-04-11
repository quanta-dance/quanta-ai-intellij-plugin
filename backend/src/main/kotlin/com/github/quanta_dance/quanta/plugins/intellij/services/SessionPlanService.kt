// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.project.Project

class SessionPlanService(private val project: Project) {
    fun loadText(maxChars: Int = 16_000): String = ""
    fun ensureExistsDraft(goal: String = "", definitionOfDone: String = "", tasks: List<String> = emptyList()) {}
    fun saveDraft(goal: String, definitionOfDone: String, tasks: List<String>) {}
    fun activate() {}
    fun markTasksDone(completed: List<String>): Boolean = false
    fun saveDone() {}
    fun saveActive() {}
    fun getStatus(): String = "ACTIVE"
}
