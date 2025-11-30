// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.tools.models

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@JsonClassDescription("ReadFileContent operation result.")
data class ReadFileResult(
    @JsonPropertyDescription("File content format to understand how to parse it.")
    val format: String,
    @JsonPropertyDescription("File content when operation was successful.")
    val content: String,
    @JsonPropertyDescription("Error message if operation was not successful.")
    val error: String = "",
    @JsonPropertyDescription("File Version")
    val fileVersion: Long? = null,
)
