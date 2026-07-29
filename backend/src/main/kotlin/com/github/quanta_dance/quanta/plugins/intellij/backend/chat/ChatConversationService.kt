// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentRegistryService
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.project.CurrentFileContextProvider
import com.github.quanta_dance.quanta.plugins.intellij.backend.repository.ChatMessageFactory
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AiInputSanitizer
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.BackendExecutionContextsService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.OpenAIService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.ProjectAgentsFileManager
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.SessionPlanService
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ToolsRegistry
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage.ChatMessageType.AI_THINKING
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.toChatMessageDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.beans.PropertyChangeListener
import kotlin.coroutines.cancellation.CancellationException

/**
 * Backend conversation orchestrator for the main Quanta chat session.
 *
 * This service restores persisted session state, coordinates message flow with [OpenAIService], and
 * bridges agent lifecycle events into chat-visible updates consumed by the frontend.
 */
@Service(Service.Level.PROJECT)
class ChatConversationService(
    private val project: Project,
) : Disposable {
    private val aiInputSanitizer = AiInputSanitizer(project)

    @Suppress("ktlint:standard:backing-property-naming")
    private val chatMessageFactory = ChatMessageFactory("Quanta AI", "Me")
    private val openAIService: OpenAIService get() = project.service()
    private val agentManager: AgentManagerService get() = project.service()
    private val registry: AgentRegistryService get() = project.service()
    private val persistence: ChatConversationStateService get() = project.service()
    private val executionContexts: BackendExecutionContextsService get() = project.service()

    @Suppress("ktlint:standard:backing-property-naming")
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    @Suppress("ktlint:standard:backing-property-naming")
    private val _sessions = MutableStateFlow<List<ChatSessionDto>>(emptyList())

    private val agentTaskListener =
        PropertyChangeListener { event ->
            when (event.propertyName) {
                "agent_task_started" -> {
                    val payload = event.newValue as? Map<*, *> ?: return@PropertyChangeListener
                    val agentId = payload["agentId"] as? String ?: return@PropertyChangeListener
                    appendAgentThreadMessage(
                        agentId = agentId,
                        content = "Started delegated task",
                    )
                }

                "agent_task_finished" -> {
                    val result = event.newValue as? AgentManagerService.AgentTaskResult ?: return@PropertyChangeListener
                    appendAgentThreadMessage(
                        agentId = result.agentId,
                        content = result.text ?: result.error ?: "Completed delegated task",
                    )
                }
            }
        }

    init {
        persistence.ensureSessionExists()
        _messages.value = persistence.loadActiveMessages()
        _sessions.value = persistence.listSessions()
        openAIService.switchToSession(persistence.getActiveSessionId(), persistence.getActiveLastResponseId())
        agentManager.reloadAgentsFromSession()
        agentManager.addPropertyChangeListener(agentTaskListener)
    }

    fun messagesFlow(): Flow<List<ChatMessageDto>> =
        _messages.map { messagesList ->
            messagesList.map { it.toChatMessageDto() }
        }

    fun currentMessages(): List<ChatMessageDto> = _messages.value.map { it.toChatMessageDto() }

    fun sessionsFlow(): Flow<List<ChatSessionDto>> = _sessions

    fun currentSessions(): List<ChatSessionDto> = _sessions.value

    override fun dispose() {
        agentManager.removePropertyChangeListener(agentTaskListener)
    }

    private fun <T> onChatPublicationThread(action: () -> T): T = runBlocking(executionContexts.chatPublicationDispatcher) { action() }

    fun createNewSession() {
        onChatPublicationThread {
            persistence.saveActiveAgents(agentManager.getPersistedAgentProfiles())
            val sessionId = persistence.createSession()
            _messages.value = emptyList()
            _sessions.value = persistence.listSessions()
            openAIService.switchToSession(sessionId, null)
            project.service<SessionPlanService>().publishCurrentStatus()
            agentManager.reloadAgentsFromSession()
        }
    }

    fun stopAllAgents(): Int {
        val stopped = agentManager.stopAllAgents()
        val affectedTasks = project.service<AgentChannelStateService>().stopAllAgentWork()
        return maxOf(stopped, affectedTasks)
    }

    fun activateSession(sessionId: String) {
        onChatPublicationThread {
            persistence.saveActiveAgents(agentManager.getPersistedAgentProfiles())
            if (!persistence.activateSession(sessionId)) return@onChatPublicationThread
            _messages.value = persistence.loadActiveMessages()
            _sessions.value = persistence.listSessions()
            openAIService.switchToSession(sessionId, persistence.getActiveLastResponseId())
            project.service<SessionPlanService>().publishCurrentStatus()
            agentManager.reloadAgentsFromSession()
        }
    }

    fun deleteSession(sessionId: String) {
        onChatPublicationThread {
            persistence.saveActiveAgents(agentManager.getPersistedAgentProfiles())
            val nextSessionId = persistence.deleteSession(sessionId)
            _messages.value = persistence.loadActiveMessages()
            _sessions.value = persistence.listSessions()
            openAIService.switchToSession(nextSessionId, persistence.getActiveLastResponseId())
            project.service<SessionPlanService>().publishCurrentStatus()
            agentManager.reloadAgentsFromSession()
        }
    }

    suspend fun sendUserMessage(messageContent: String) {
        withContext(Dispatchers.IO) {
            var thinkingMessageId: String
            var toolMessageId: String? = null
            var firstAssistantMessageShown = false
            try {
                QDLog.info(
                    com.intellij.openapi.diagnostic.Logger
                        .getInstance(ChatConversationService::class.java),
                ) {
                    "ChatConversationService.sendUserMessage: user='${
                        messageContent.replace("\n", "\\n").take(2_000)
                    }'"
                }
                val sanitizedMessageContent = aiInputSanitizer.sanitizeForAi(messageContent)
                appendUserMessage(
                    messageContent = messageContent,
                    sanitizedForAiContent = sanitizedMessageContent.takeIf { it != messageContent },
                )
                val inputs = buildRequestInputs()
                thinkingMessageId = appendAiThinkingMessage()
                val (responseText, _) =
                    try {
                        awaitManagerTurn(
                            inputs = inputs,
                            thinkingMessageIdProvider = { thinkingMessageId },
                            onThinkingMessageIdChanged = { thinkingMessageId = it },
                            toolMessageIdProvider = { toolMessageId },
                            onToolMessageIdChanged = { toolMessageId = it },
                            onFirstAssistantMessageShown = { firstAssistantMessageShown = true },
                        )
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        if (!isContextWindowError(e)) throw e

                        clearThinkingMessages()
                        compactConversationWithBrief("")
                        thinkingMessageId = appendAiThinkingMessage()
                        toolMessageId = null
                        firstAssistantMessageShown = false

                        awaitManagerTurn(
                            inputs =
                                buildCompactedRetryInputs(
                                    brief = "",
                                    messageContent = messageContent,
                                    sanitizedForAiContent = sanitizedMessageContent.takeIf { it != messageContent },
                                ),
                            thinkingMessageIdProvider = { thinkingMessageId },
                            onThinkingMessageIdChanged = { thinkingMessageId = it },
                            toolMessageIdProvider = { toolMessageId },
                            onToolMessageIdChanged = { toolMessageId = it },
                            onFirstAssistantMessageShown = { firstAssistantMessageShown = true },
                        )
                    }
                if (!firstAssistantMessageShown) {
                    replaceMessage(
                        thinkingMessageId,
                        chatMessageFactory.createAIMessage(responseText),
                    )
                } else {
                    clearThinkingMessages()
                }
                persistMessages()
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    clearThinkingMessages()
                    throw e
                }
                val errorText =
                    buildString {
                        append("Backend error: ")
                        append(e::class.java.simpleName)
                        val message = e.message?.trim().orEmpty()
                        if (message.isNotEmpty()) {
                            append(" - ").append(message)
                        }
                        append(e.stackTrace.joinToString("\n"))
                    }
                clearThinkingMessages()
                appendAiMessage(errorText)
            }
        }
    }

    private fun buildRequestInputs(): MutableList<ResponseInputItem> = buildInputsFromTurns(buildHistory())

    private fun buildCompactedRetryInputs(
        brief: String,
        messageContent: String,
        sanitizedForAiContent: String?,
    ): MutableList<ResponseInputItem> =
        buildInputsFromTurns(
            listOf(
                ChatTurn(
                    role = "assistant",
                    content = "Conversation compacted. Continue using this session brief for prior context:\n$brief",
                ),
                ChatTurn(
                    role = "user",
                    content = messageContent,
                    sanitizedForAiContent = sanitizedForAiContent,
                ),
            ),
        )

    private fun buildInputsFromTurns(turns: List<ChatTurn>): MutableList<ResponseInputItem> =
        buildList {
            buildContextMessage()?.let { contextMessage ->
                add(
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage
                            .builder()
                            .role(EasyInputMessage.Role.SYSTEM)
                            .content(aiInputSanitizer.sanitizeForAi(contextMessage))
                            .build(),
                    ),
                )
            }
            turns.forEach { turn ->
                add(
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage
                            .builder()
                            .role(if (turn.role == "user") EasyInputMessage.Role.USER else EasyInputMessage.Role.ASSISTANT)
                            .content(turn.sanitizedForAiContent ?: aiInputSanitizer.sanitizeForAi(turn.content))
                            .build(),
                    ),
                )
            }
        }.toMutableList()

    private fun appendUserMessage(
        messageContent: String,
        sanitizedForAiContent: String? = null,
    ) {
        onChatPublicationThread {
            _messages.value +=
                chatMessageFactory.createUserMessage(
                    content = messageContent,
                    sanitizedForAiContent = sanitizedForAiContent,
                )
            persistMessages()
        }
    }

    private fun appendAgentThreadMessage(
        agentId: String,
        content: String,
    ) {
        onChatPublicationThread {
            val agent = registry.getAgentsSnapshot().firstOrNull { it.id == agentId }
            val aiMessage =
                chatMessageFactory
                    .createAIMessage(
                        content = content,
                        parentMessageId = null,
                    ).copy(author = agent?.role ?: "Agent")
            _messages.value += aiMessage
            persistMessages()
        }
    }

    private fun appendAiMessage(messageContent: String) {
        onChatPublicationThread {
            _messages.value += chatMessageFactory.createAIMessage(messageContent)
            persistMessages()
        }
    }

    fun appendAiToolMessage(
        toolItems: List<com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem>,
        beforeMessageId: String? = null,
    ): String =
        onChatPublicationThread {
            if (toolItems.isEmpty()) return@onChatPublicationThread ""
            val newMessages = toolItems.map { item -> chatMessageFactory.createAIToolMessage(listOf(item)) }
            _messages.value =
                if (beforeMessageId == null) {
                    _messages.value + newMessages
                } else {
                    val idx = _messages.value.indexOfFirst { it.id == beforeMessageId }
                    if (idx < 0) {
                        _messages.value + newMessages
                    } else {
                        buildList {
                            addAll(_messages.value.take(idx))
                            addAll(newMessages)
                            addAll(_messages.value.drop(idx))
                        }
                    }
                }
            persistMessages()
            newMessages.first().id
        }

    private fun currentToolItems(
        messageId: String,
    ): List<com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem> =
        _messages.value
            .firstOrNull { it.id == messageId }
            ?.toolItems
            .orEmpty()

    private fun mergeToolItems(
        existing: List<com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem>,
        incoming: com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem,
    ): List<com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem> {
        val idx = existing.indexOfFirst { it.callId == incoming.callId }
        return if (idx >= 0) {
            existing.toMutableList().apply { this[idx] = incoming }
        } else {
            existing + incoming
        }
    }

    private fun appendAiThinkingMessage(): String =
        onChatPublicationThread {
            val message =
                ChatMessage(
                    content = "AI is thinking…",
                    author = "AI Manager",
                    type = AI_THINKING,
                )
            _messages.value += message
            persistMessages()
            message.id
        }

    private fun replaceMessage(
        messageId: String,
        newMessage: ChatMessage,
    ) {
        onChatPublicationThread {
            _messages.value =
                _messages.value.map { message ->
                    if (message.id == messageId) {
                        newMessage.copy(id = messageId)
                    } else {
                        message
                    }
                }
            persistMessages()
        }
    }

    private fun clearThinkingMessages() {
        onChatPublicationThread {
            _messages.value = _messages.value.filterNot { it.type == AI_THINKING }
            persistMessages()
        }
    }

    suspend fun sendScheduledReminder(reminderContext: String) {
        withContext(Dispatchers.IO) {
            val thinkingMessageId = appendAiThinkingMessage()
            try {
                val inputs = buildReminderRequestInputs(reminderContext)
                val (responseText, _) =
                    awaitDetachedAgentTurn {
                        openAIService.agentTurn(
                            inputs = inputs,
                            previousId = null,
                            agentLabel = "AI Manager",
                        )
                    }
                replaceMessage(
                    thinkingMessageId,
                    chatMessageFactory.createAIMessage(responseText),
                )
                persistMessages()
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    clearThinkingMessages()
                    throw e
                }
                replaceMessage(
                    thinkingMessageId,
                    chatMessageFactory.createAIMessage(
                        "I want to remind you: ${
                            reminderContext.trim().removePrefix("Reminder:").trim()
                                .ifBlank { "please check your reminder." }
                        }",
                    ),
                )
                persistMessages()
            }
        }
    }

    private fun buildReminderRequestInputs(reminderContext: String): MutableList<ResponseInputItem> =
        buildRequestInputs().apply {
            add(
                ResponseInputItem.ofMessage(
                    ResponseInputItem.Message
                        .builder()
                        .addInputTextContent(
                            "Scheduled reminder context (internal only):\n" +
                                reminderContext +
                                "\n\nWrite a short, natural reminder to the user. " +
                                "Do not say the reminder was acknowledged, delivered, fired, or triggered. " +
                                "Do not repeat the reminder context verbatim. " +
                                "Use first-person phrasing like 'I want to remind you ...'.",
                        ).role(ResponseInputItem.Message.Role.SYSTEM)
                        .build(),
                ),
            )
        }

    private suspend fun <T> awaitDetachedAgentTurn(block: () -> T): T {
        val deferred =
            executionContexts.agentOrchestrationScope.async {
                block()
            }
        return withContext(NonCancellable) {
            deferred.await()
        }
    }

    private fun buildHistory(): List<ChatTurn> =
        _messages.value
            .filter { it.isMyMessage || it.isTextMessage() }
            .map { message ->
                val role = if (message.isMyMessage) "user" else "assistant"
                ChatTurn(
                    role = role,
                    content = message.content,
                    sanitizedForAiContent = message.sanitizedForAiContent,
                )
            }

    private suspend fun awaitManagerTurn(
        inputs: MutableList<ResponseInputItem>,
        thinkingMessageIdProvider: () -> String,
        onThinkingMessageIdChanged: (String) -> Unit,
        toolMessageIdProvider: () -> String?,
        onToolMessageIdChanged: (String?) -> Unit,
        onFirstAssistantMessageShown: () -> Unit,
    ): Pair<String, String?> {
        val toolMessageIdsByCallId = mutableMapOf<String, String>()

        return awaitDetachedAgentTurn {
            openAIService.agentTurn(
                inputs = inputs,
                previousId = null,
                agentLabel = "AI Manager",
                onAssistantMessage = { assistantMessage ->
                    val visibleContent =
                        if (assistantMessage.isReasoning) {
                            "Reasoning\n${assistantMessage.text}"
                        } else {
                            assistantMessage.text
                        }
                    replaceMessage(
                        thinkingMessageIdProvider(),
                        chatMessageFactory.createAIMessage(
                            content = visibleContent,
                            voiceSummary = assistantMessage.ttsSummary,
                        ),
                    )
                    onFirstAssistantMessageShown()
                    onToolMessageIdChanged(null)
                    onThinkingMessageIdChanged(appendAiThinkingMessage())
                },
                onToolUpdate = { update ->
                    val targetId =
                        toolMessageIdsByCallId.getOrPut(update.item.callId) {
                            appendAiToolMessage(
                                toolItems = listOf(update.item),
                                beforeMessageId = thinkingMessageIdProvider(),
                            )
                        }
                    replaceMessage(
                        targetId,
                        chatMessageFactory.createAIToolMessage(listOf(update.item)),
                    )
                },
            )
        }
    }

    private fun isContextWindowError(t: Throwable): Boolean {
        val msg = t.message.orEmpty()
        return msg.contains("exceeds context window", ignoreCase = true) ||
            msg.contains("context window", ignoreCase = true)
    }

    private fun buildContextMessage(): String? {
        val ctx = runCatching { CurrentFileContextProvider(project).getCurrent() }.getOrNull() ?: return null
        val caretSuffix =
            buildString {
                val caretLine = ctx.caretLine
                val caretCol = ctx.caretColumn
                if (caretLine != null && caretCol != null) {
                    append("; caret line ").append(caretLine).append(", column ").append(caretCol)
                }
            }

        return buildString {
            append("Current file open: ")
            append(ctx.filePathRelative)
            append("; file hash sha256: ")
            append(ctx.fileHashSha256)
            append("; reread the file if the hash changed")
            append(caretSuffix)
            ctx.selectedText?.takeIf { it.isNotBlank() }?.let { selectedText ->
                append("\nSelected text:\n")
                append(selectedText.take(2_000))
            }
        }
    }

    private data class ChatTurn(
        val role: String,
        val content: String,
        val sanitizedForAiContent: String? = null,
    )

    fun clearConversation() {
        _messages.value = emptyList()
        persistence.clearActiveSession()
        persistMessages()
    }

    fun compactConversationWithBrief(brief: String) {
        val agentsText =
            runCatching { ProjectAgentsFileManager(project).readAgentsFile(maxChars = 8_000) }
                .getOrNull()
                .orEmpty()
        val toolCount = runCatching { ToolsRegistry.toolsFor(project).size }.getOrDefault(0)
        val compactedDetails =
            buildString {
                append("Conversation compacted.")
                append(" Continuing from linear history.")
                append("\n\nTool inventory refresh:\n")
                append("Current tool count: ").append(toolCount)
                if (agentsText.isNotBlank()) {
                    append("\n\nAGENTS.md refresh:\n")
                    append(agentsText.take(2_000))
                }
                if (brief.isNotBlank()) {
                    append("\n\nSession brief:\n")
                    append(brief.take(1_200))
                }
            }
        val compactionToolItem =
            ToolExecutionItem(
                callId = "session-compaction",
                toolName = "ConversationCompaction",
                displayText = "Compaction completed",
                status = ToolExecutionStatus.SUCCEEDED,
                detailText = compactedDetails,
            )
        val toolMessage = chatMessageFactory.createAIToolMessage(listOf(compactionToolItem))
        val notice = chatMessageFactory.createAIMessage(compactedDetails)
        _messages.value = listOf(toolMessage, notice)
        persistMessages()
    }

    private fun persistMessages() {
        persistence.saveActiveMessages(_messages.value, openAIService.getLastResponseId())
        _sessions.value = persistence.listSessions()
    }
}
