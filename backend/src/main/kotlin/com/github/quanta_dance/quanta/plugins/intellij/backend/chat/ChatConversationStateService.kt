package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.intellij.openapi.components.*
import java.time.LocalDateTime

@Service(Service.Level.PROJECT)
@State(
    name = "QuantaChatConversationState",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class ChatConversationStateService : PersistentStateComponent<ChatConversationStateService.State> {
    data class PersistedToolItem(
        var callId: String = "",
        var toolName: String = "",
        var displayText: String = "",
        var status: String = ToolExecutionStatus.EXECUTING.name,
        var filePath: String? = null,
        var errorText: String? = null,
    )

    data class PersistedChatMessage(
        var id: String = "",
        var content: String = "",
        var author: String = "",
        var isMyMessage: Boolean = false,
        var timestamp: String = "",
        var type: String = ChatMessage.ChatMessageType.TEXT.name,
        var voiceSummary: String? = null,
        var toolItems: MutableList<PersistedToolItem> = mutableListOf(),
    )

    data class State(
        var messages: MutableList<PersistedChatMessage> = mutableListOf(),
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun loadMessages(): List<ChatMessage> =
        state.messages.mapNotNull { saved ->
            runCatching {
                ChatMessage(
                    id = saved.id,
                    content = saved.content,
                    author = saved.author,
                    isMyMessage = saved.isMyMessage,
                    timestamp = LocalDateTime.parse(saved.timestamp),
                    type = ChatMessage.ChatMessageType.valueOf(saved.type),
                    voiceSummary = saved.voiceSummary,
                    toolItems =
                        saved.toolItems.map { item ->
                            ToolExecutionItem(
                                callId = item.callId,
                                toolName = item.toolName,
                                displayText = item.displayText,
                                status = ToolExecutionStatus.valueOf(item.status),
                                filePath = item.filePath,
                                errorText = item.errorText,
                            )
                        },
                )
            }.getOrNull()
        }

    fun saveMessages(messages: List<ChatMessage>) {
        state.messages =
            messages.map { message ->
                PersistedChatMessage(
                    id = message.id,
                    content = message.content,
                    author = message.author,
                    isMyMessage = message.isMyMessage,
                    timestamp = message.timestamp.toString(),
                    type = message.type.name,
                    voiceSummary = message.voiceSummary,
                    toolItems =
                        message.toolItems.map { item ->
                            PersistedToolItem(
                                callId = item.callId,
                                toolName = item.toolName,
                                displayText = item.displayText,
                                status = item.status.name,
                                filePath = item.filePath,
                                errorText = item.errorText,
                            )
                        }.toMutableList(),
                )
            }.toMutableList()
    }

    fun clear() {
        state.messages.clear()
    }
}
