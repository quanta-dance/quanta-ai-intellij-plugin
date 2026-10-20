// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentChannelEventDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentInfoDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationParticipantDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.LocalQuantaAcpSessionDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.McpServerStatusDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChatViewModelTest {
    @Test
    fun `coalesces follow-ups submitted while the current message is still sending`() =
        runBlocking {
            val firstSubmissionStarted = CompletableDeferred<Unit>()
            val secondSubmissionStarted = CompletableDeferred<Unit>()
            val releaseFirstSubmission = CompletableDeferred<Unit>()
            val releaseSecondSubmission = CompletableDeferred<Unit>()
            val submittedMessages = mutableListOf<String>()
            val repository = mockRepository()
            coEvery { repository.sendMessage(any()) } coAnswers {
                val message = firstArg<String>()
                submittedMessages += message
                when (message) {
                    "first" -> {
                        firstSubmissionStarted.complete(Unit)
                        releaseFirstSubmission.await()
                    }

                    "follow-up one\n\nfollow-up two" -> {
                        secondSubmissionStarted.complete(Unit)
                        releaseSecondSubmission.await()
                    }
                }
            }
            val viewModel =
                ChatViewModel(
                    coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    repository = repository,
                )

            viewModel.onPromptInputChanged("first")
            viewModel.onSendMessage()
            firstSubmissionStarted.await()

            viewModel.onPromptInputChanged("follow-up one")
            viewModel.onSendMessage()
            viewModel.onPromptInputChanged("follow-up two")
            viewModel.onSendMessage()

            assertEquals(listOf("first"), submittedMessages)
            assertIs<MessageInputState.Sending>(viewModel.promptInputState.value)

            releaseFirstSubmission.complete(Unit)
            secondSubmissionStarted.await()
            assertEquals(listOf("first", "follow-up one\n\nfollow-up two"), submittedMessages)
            assertIs<MessageInputState.Sending>(viewModel.promptInputState.value)

            releaseSecondSubmission.complete(Unit)
            yield()
            assertEquals(MessageInputState.Disabled, viewModel.promptInputState.value)
            viewModel.dispose()
        }

    private fun mockRepository(): ChatRepositoryApi =
        mockk {
            every { messagesFlow } returns MutableStateFlow(emptyList<ChatMessage>())
            every { sessionsFlow } returns MutableStateFlow(emptyList<ChatSessionDto>())
            every { planStatusFlow } returns MutableStateFlow(ChatPlanStatusDto())
            every { agentsFlow } returns MutableStateFlow(emptyList<AgentInfoDto>())
            every { collaborationParticipantsFlow } returns MutableStateFlow(emptyList<CollaborationParticipantDto>())
            every { acpAgentsFlow } returns MutableStateFlow(emptyList<AcpAgentDto>())
            every { acpDiscoveryLoadingFlow } returns MutableStateFlow(false)
            every { allowedAcpAgentIdsFlow } returns MutableStateFlow(emptySet())
            every { mcpServersFlow } returns MutableStateFlow(emptyList<McpServerStatusDto>())
            every { mcpConfigurationLoadingFlow } returns MutableStateFlow(false)
            every { mcpConfigurationErrorFlow } returns MutableStateFlow<String?>(null)
            every { delegatedTasksFlow } returns MutableStateFlow(emptyList<DelegatedTaskDto>())
            every { channelEventsFlow } returns MutableStateFlow(emptyList<AgentChannelEventDto>())
            every { quantaAcpInviteFlow } returns MutableStateFlow<String?>(null)
            every { availableLocalQuantaAcpSessionsFlow } returns MutableStateFlow(emptyList<LocalQuantaAcpSessionDto>())
        }
}
