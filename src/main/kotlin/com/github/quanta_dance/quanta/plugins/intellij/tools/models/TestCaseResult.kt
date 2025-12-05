// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.models

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@JsonClassDescription("Single test case result information")
data class TestCaseResult(
    @field:JsonPropertyDescription("Fully qualified test class name")
    val className: String,
    @field:JsonPropertyDescription("Test method name")
    val name: String,
    @field:JsonPropertyDescription("Test status: passed, failed, skipped, error")
    val status: String,
    @field:JsonPropertyDescription("Duration in milliseconds, if available")
    val durationMillis: Long? = null,
    @field:JsonPropertyDescription("Failure message, if failed/error")
    val failureMessage: String? = null,
    @field:JsonPropertyDescription("Failure type/class, if available")
    val failureType: String? = null,
    @field:JsonPropertyDescription("Failure stacktrace, if available")
    val failureStackTrace: String? = null,
    @field:JsonPropertyDescription("System.out captured for this test, if available")
    val systemOut: String? = null,
    @field:JsonPropertyDescription("System.err captured for this test, if available")
    val systemErr: String? = null,
    @field:JsonPropertyDescription("Path to the source XML report file for this test case")
    val reportFilePath: String? = null,
)
