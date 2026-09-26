// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

/** Versioned, transport-neutral identity of a participant in one chat collaboration roster. */
@Serializable
data class CollaborationParticipantDto(
    /** Session-scoped stable ID, for example manager:<chatId>, local:<agentId>, or acp:<agentId>. */
    val id: String,
    val displayName: String,
    val kind: CollaborationParticipantKindDto,
    val capabilities: Set<CollaborationCapabilityDto> = emptySet(),
    val availability: CollaborationAvailabilityDto = CollaborationAvailabilityDto.UNKNOWN,
    /** Original local-agent or ACP roster ID, retained only for the backend transport adapter. */
    val transportId: String? = null,
)

@Serializable
enum class CollaborationParticipantKindDto {
    MANAGER,
    LOCAL_AGENT,
    ACP_AGENT,
}

@Serializable
enum class CollaborationCapabilityDto {
    NOTIFICATIONS,
    TASKS,
    RESULTS,
    STATUS_UPDATES,
    CANCELLATION,
}

@Serializable
enum class CollaborationAvailabilityDto {
    AVAILABLE,
    BUSY,
    OFFLINE,
    UNKNOWN,
}

/** One addressable collaboration event. Content is transport-neutral and correlation-safe. */
@Serializable
data class CollaborationMessageDto(
    val id: String,
    val sessionId: String,
    val senderId: String,
    val recipientId: String,
    val intent: CollaborationIntentDto,
    val text: String,
    val taskId: String? = null,
    val parentTaskId: String? = null,
    val rootTaskId: String? = null,
    val replyToMessageId: String? = null,
    val correlationId: String? = null,
    val createdAtEpochMs: Long,
)

@Serializable
enum class CollaborationIntentDto {
    NOTIFICATION,
    TASK,
    RESULT,
    STATUS,
    CANCEL,
}

/** Lifecycle used by collaboration routing independently of the underlying local or ACP transport. */
@Serializable
enum class CollaborationTaskStatusDto {
    QUEUED,
    RUNNING,
    WAITING_FOR_DEPENDENCIES,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

/** Normalized terminal or progress payload emitted by every collaboration transport adapter. */
@Serializable
data class CollaborationTaskUpdateDto(
    val taskId: String,
    val senderId: String,
    val recipientId: String,
    val status: CollaborationTaskStatusDto,
    val text: String? = null,
    val error: String? = null,
    val correlationId: String? = null,
    val updatedAtEpochMs: Long,
)
