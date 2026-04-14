package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class AIVoiceService(@Suppress("unused") private val project: Project) {
    fun say(@Suppress("unused") message: String) = Unit
    fun stopTalking() = Unit
}
