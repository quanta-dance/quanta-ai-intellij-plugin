// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import kotlinx.serialization.Serializable

@Serializable
data class SessionMemoryFacts(
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
