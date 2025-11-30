// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.tools.models

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@JsonClassDescription("Result of running Gradle tests")
data class RunTestsResult(
    @JsonPropertyDescription("Whether gradle test execution completed successfully (exit code 0)")
    val success: Boolean,
    @JsonPropertyDescription("Total number of test cases found in reports")
    val totalCount: Int,
    @JsonPropertyDescription("Number of failed test cases")
    val failedCount: Int,
    @JsonPropertyDescription("Number of skipped/ignored test cases")
    val skippedCount: Int,
    @JsonPropertyDescription("List of failed test cases with details")
    val failedTests: List<TestCaseResult> = emptyList(),
    @JsonPropertyDescription("Optional tail of stdout from gradle, for quick context")
    val stdoutTail: String? = null,
    @JsonPropertyDescription("Optional error message if gradle failed")
    val error: String = "",
)
