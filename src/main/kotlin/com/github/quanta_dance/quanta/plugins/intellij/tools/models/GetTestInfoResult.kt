// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.models

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@JsonClassDescription("Detailed information for a specific test case from Gradle XML reports")
data class GetTestInfoResult(
    @field:JsonPropertyDescription("Error message if operation was not successful")
    val error: String = "",
    @field:JsonPropertyDescription("Test case information if found")
    val test: TestCaseResult? = null,
)
