// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.tools.models

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@JsonClassDescription("Single test case result information")
data class TestCaseResult(
    @JsonPropertyDescription("Fully qualified test class name")
    val className: String,
    @JsonPropertyDescription("Test method name")
    val name: String,
    @JsonPropertyDescription("Test status: passed, failed, skipped, error")
    val status: String,
    @JsonPropertyDescription("Duration in milliseconds, if available")
    val durationMillis: Long? = null,
    @JsonPropertyDescription("Failure message, if failed/error")
    val failureMessage: String? = null,
    @JsonPropertyDescription("Failure type/class, if available")
    val failureType: String? = null,
    @JsonPropertyDescription("Failure stacktrace, if available")
    val failureStackTrace: String? = null,
    @JsonPropertyDescription("System.out captured for this test, if available")
    val systemOut: String? = null,
    @JsonPropertyDescription("System.err captured for this test, if available")
    val systemErr: String? = null,
    @JsonPropertyDescription("Path to the source XML report file for this test case")
    val reportFilePath: String? = null,
)
