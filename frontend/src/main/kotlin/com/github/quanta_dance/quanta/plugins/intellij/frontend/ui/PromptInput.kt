package com.github.quanta_dance.quanta.plugins.intellij.frontend.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatAppColors
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatAppIcons
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.MessageInputState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.isSending
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.theme.iconButtonStyle

@Composable
fun PromptInput(
    modifier: Modifier = Modifier,
    promptInputState: MessageInputState = MessageInputState.Disabled,
    textFieldState: TextFieldState = rememberTextFieldState(),
    hint: String = "Whats on your mind...",
    voiceEnabled: Boolean = true,
    micEnabled: Boolean = false,
    micActive: Boolean = false,
    currentModel: String = "",
    availableModels: List<String> = emptyList(),
    onModelSelected: (String) -> Unit = {},
    onToggleMic: () -> Unit = {},
    onToggleVoiceFeedback: () -> Unit = {},
    onInputChanged: (String) -> Unit = {},
    onSend: (String) -> Unit = {},
    onStop: (String) -> Unit = {}
) {
    val isSending = promptInputState.isSending
    var skipInputChangeUpdate by remember { mutableStateOf(false) }
    var localVoiceEnabled by remember { mutableStateOf(voiceEnabled) }

    LaunchedEffect(voiceEnabled) {
        localVoiceEnabled = voiceEnabled
    }

    LaunchedEffect(Unit) {
        snapshotFlow { textFieldState.text }
            .distinctUntilChanged()
            .collect { inputText ->
                if (skipInputChangeUpdate) {
                    skipInputChangeUpdate = false
                    return@collect
                }

                onInputChanged(inputText.toString())
            }
    }

    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        TextArea(
            state = textFieldState,
            modifier = Modifier
                .weight(0.75f)
                .fillMaxWidth()
                .padding(bottom = 4.dp)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                        if (keyEvent.isShiftPressed) {
                            // Shift+Enter for new line - let default behavior handle it
                            skipInputChangeUpdate = true
                            textFieldState.setTextAndPlaceCursorAtEnd("${textFieldState.text}\n")
                            false
                        } else {
                            // Enter to send/update message
                            val message = textFieldState.text
                            if (message.isNotBlank()) {
                                if (isSending) {
                                    onStop(message.toString())
                                } else {
                                    onSend(message.toString())
                                    skipInputChangeUpdate = true
                                    textFieldState.setTextAndPlaceCursorAtEnd("")
                                }
                            }
                            true
                        }
                    } else {
                        false
                    }
                },
            placeholder = { Text(hint) },
        )

        Row(
            modifier = Modifier
                .weight(0.25f)
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            when (promptInputState) {
                MessageInputState.Disabled,
                is MessageInputState.Enabled,
                is MessageInputState.SendFailed,
                is MessageInputState.Sent -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (availableModels.isNotEmpty()) {
                            key(currentModel) {
                                ComboBox(
                                    labelText = currentModel,
                                    modifier = Modifier.widthIn(min = 120.dp).padding(end = 6.dp),
                                    popupContent = {
                                        Column {
                                            availableModels.forEach { model ->
                                                Text(
                                                    text = model,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { onModelSelected(model) }
                                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        IconButton(onClick = onToggleMic) {
                            Icon(
                                key = when {
                                    micActive -> ChatAppIcons.Header.micActive
                                    micEnabled -> ChatAppIcons.Header.micOn
                                    else -> ChatAppIcons.Header.micOff
                                },
                                contentDescription = "Toggle Microphone"
                            )
                        }

                        IconButton(onClick = onToggleVoiceFeedback) {
                            Icon(
                                key = if (voiceEnabled) ChatAppIcons.Header.speakerOn else ChatAppIcons.Header.speakerOff,
                                contentDescription = "Toggle Voice Feedback"
                            )
                        }

                        DefaultButton(
                            modifier = Modifier.wrapContentSize(),
                            enabled = promptInputState != MessageInputState.Disabled,
                            onClick = {
                                onSend(textFieldState.text.toString())
                                skipInputChangeUpdate = true
                                textFieldState.setTextAndPlaceCursorAtEnd("")
                            },
                            content = {
                                Row(
                                    Modifier.padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Text("Send")
                                    Icon(
                                        modifier = Modifier.size(JewelTheme.iconButtonStyle.metrics.minSize.height),
                                        key = ChatAppIcons.Prompt.send,
                                        contentDescription = "Send",
                                        tint = if (promptInputState != MessageInputState.Disabled) ChatAppColors.Icon.enabledIconTint else ChatAppColors.Icon.disabledIconTint
                                    )
                                }
                            }
                        )
                    }
                }

                is MessageInputState.Sending -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (availableModels.isNotEmpty()) {
                            key(currentModel) {
                                ComboBox(
                                    labelText = currentModel,
                                    modifier = Modifier.widthIn(min = 120.dp).padding(end = 6.dp),
                                    popupContent = {
                                        Column {
                                            availableModels.forEach { model ->
                                                Text(
                                                    text = model,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { onModelSelected(model) }
                                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        IconButton(onClick = onToggleMic) {
                            Icon(
                                key = when {
                                    micActive -> ChatAppIcons.Header.micActive
                                    micEnabled -> ChatAppIcons.Header.micOn
                                    else -> ChatAppIcons.Header.micOff
                                },
                                contentDescription = "Toggle Microphone"
                            )
                        }

                        IconButton(onClick = {
                            localVoiceEnabled = !localVoiceEnabled
                            onToggleVoiceFeedback()
                        }) {
                            Icon(
                                key = if (localVoiceEnabled) ChatAppIcons.Header.speakerOn else ChatAppIcons.Header.speakerOff,
                                contentDescription = "Toggle Voice Feedback"
                            )
                        }

                        OutlinedButton(
                            modifier = Modifier.wrapContentSize(),
                            onClick = { onStop(textFieldState.text.toString()) },
                            content = {
                                Row(
                                    Modifier.padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Text("Stop")
                                    Icon(
                                        modifier = Modifier.size(JewelTheme.iconButtonStyle.metrics.minSize.height),
                                        key = ChatAppIcons.Prompt.stop,
                                        contentDescription = "Stop sending",
                                        tint = ChatAppColors.Icon.stopIconTint
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
