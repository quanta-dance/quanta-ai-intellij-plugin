package com.github.quanta_dance.quanta.plugins.intellij.tools.models

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@JsonClassDescription("Detailed information for a specific test case from Gradle XML reports")
data class GetTestInfoResult(
    @JsonPropertyDescription("Error message if operation was not successful")
    val error: String = "",
    @JsonPropertyDescription("Test case information if found")
    val test: TestCaseResult? = null,
)
