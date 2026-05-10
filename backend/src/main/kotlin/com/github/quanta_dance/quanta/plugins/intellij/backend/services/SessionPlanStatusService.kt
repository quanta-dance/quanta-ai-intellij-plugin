package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.intellij.openapi.components.Service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backend-facing state holder for the current session plan execution status.
 *
 * This service exposes a hot StateFlow so other backend components can publish
 * and observe the current plan status without tightly coupling to the UI.
 */
@Service(Service.Level.PROJECT)
class SessionPlanStatusService {
    /** The latest published plan status. */
    private val _status = MutableStateFlow(ChatPlanStatusDto())

    /** Stream of plan status updates for backend/GUI consumers. */
    val statusFlow: StateFlow<ChatPlanStatusDto> = _status.asStateFlow()

    /**
     * Publish a new plan status snapshot.
     */
    fun publish(status: ChatPlanStatusDto) {
        _status.value = status
    }
}