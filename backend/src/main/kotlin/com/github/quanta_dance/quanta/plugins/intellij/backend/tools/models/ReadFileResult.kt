// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.models

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@JsonClassDescription("ReadFileContent operation result.")
data class ReadFileResult(
    @field:JsonPropertyDescription("File content format to understand how to parse it.")
    val format: String,
    @field:JsonPropertyDescription("File content when operation was successful.")
    val content: String,
    @field:JsonPropertyDescription("Error message if operation was not successful.")
    val error: String = "",
    @field:JsonPropertyDescription("Optional non-fatal note or warning for successful reads, for example when a requested range was clamped to file bounds.")
    val warning: String = "",
    @field:JsonPropertyDescription("1-based requested starting line, if any.")
    val requestedFromLine: Int? = null,
    @field:JsonPropertyDescription("1-based requested ending line, if any.")
    val requestedToLine: Int? = null,
    @field:JsonPropertyDescription("1-based actual starting line returned in content, if known.")
    val actualFromLine: Int? = null,
    @field:JsonPropertyDescription("1-based actual ending line returned in content, if known.")
    val actualToLine: Int? = null,
    @field:JsonPropertyDescription("Total number of lines in the underlying file, if known.")
    val totalFileLines: Int? = null,
    @field:JsonPropertyDescription("True when the tool had to truncate or window the requested content instead of returning the full requested slice.")
    val truncated: Boolean = false,
    @field:JsonPropertyDescription("True when lines after actualToLine still remain in the requested or file content and were not returned.")
    val hasMoreContent: Boolean = false,
    @field:JsonPropertyDescription("SHA-256 of normalized file content (\r\n/\r -> \n). Useful for patch guards.")
    val fileHashSha256: String? = null,
)
