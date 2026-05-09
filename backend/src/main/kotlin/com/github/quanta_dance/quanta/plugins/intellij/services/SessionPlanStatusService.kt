package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.intellij.openapi.components.Service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Service(Service.Level.PROJECT)
class SessionPlanStatusService {
    private val _status = MutableStateFlow(ChatPlanStatusDto())
    val statusFlow: StateFlow<ChatPlanStatusDto> = _status.asStateFlow()

    fun publish(status: ChatPlanStatusDto) {
        _status.value = status
    }
}
