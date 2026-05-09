package com.github.quanta_dance.quanta.plugins.intellij.frontend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatAppColors
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatAppIcons
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.MessageInputState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.isSending
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.theme.iconButtonStyle

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PromptInput(
    modifier: Modifier = Modifier,
    promptInputState: MessageInputState = MessageInputState.Disabled,
    textFieldState: TextFieldState = rememberTextFieldState(),
    hint: String = "Whats on your mind...",
    voiceEnabled: Boolean = true,
    micEnabled: Boolean = false,
    micActive: Boolean = false,
    currentPlanStatus: String = "",
    currentPlanText: String = "",
    currentModel: String = "",
    availableModels: List<String> = emptyList(),
    agenticEnabled: Boolean = true,
    onModelSelected: (String) -> Unit = {},
    onToggleMic: () -> Unit = {},
    onToggleAgenticMode: () -> Unit = {},
    onToggleVoiceFeedback: () -> Unit = {},
    onInputChanged: (String) -> Unit = {},
    onSend: (String) -> Unit = {},
    onStop: (String) -> Unit = {},
) {
    val isSending = promptInputState.isSending
    var skipInputChangeUpdate by remember { mutableStateOf(false) }
    var localVoiceEnabled by remember { mutableStateOf(voiceEnabled) }
    var planHovered by remember { mutableStateOf(false) }
    var planHideJob by remember { mutableStateOf<Job?>(null) }
    val displayedPlanStatus = currentPlanStatus.ifBlank { if (currentPlanText.isNotBlank()) "PLAN" else "NO PLAN" }
    val planStatusIcon =
        when (displayedPlanStatus.uppercase()) {
            "DRAFT" -> ChatAppIcons.PlanStatus.draft
            "ACTIVE" -> ChatAppIcons.PlanStatus.active
            "DONE" -> ChatAppIcons.PlanStatus.done
            else -> ChatAppIcons.PlanStatus.noPlan
        }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

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
                            skipInputChangeUpdate = true
                            textFieldState.setTextAndPlaceCursorAtEnd("${textFieldState.text}\n")
                            false
                        } else {
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
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(modifier = Modifier.padding(end = 8.dp)) {
                if (planHovered && currentPlanText.isNotBlank()) {
                    val lineCount = currentPlanText.lineSequence().count().coerceAtLeast(1)
                    val popupOffsetY = with(density) { -((lineCount.coerceAtMost(20) * 18) + 20).dp.roundToPx() }
                    Popup(offset = IntOffset(0, popupOffsetY)) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 520.dp)
                                .background(Color(0xFF2B2B2B), RoundedCornerShape(6.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = currentPlanText,
                                style = JewelTheme.defaultTextStyle.copy(
                                    fontSize = 11.sp,
                                    color = Color.White,
                                ),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .pointerMoveFilter(
                            onEnter = {
                                planHideJob?.cancel()
                                planHovered = true
                                false
                            },
                            onExit = {
                                planHideJob?.cancel()
                                planHideJob = scope.launch {
                                    delay(120)
                                    planHovered = false
                                }
                                false
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        key = planStatusIcon,
                        contentDescription = displayedPlanStatus,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "Plan",
                        style = JewelTheme.defaultTextStyle.copy(
                            fontSize = 11.sp,
                            color = Color.White,
                        ),
                    )
                }
            }

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
                                contentDescription = "Toggle Microphone",
                            )
                        }

                        OutlinedButton(
                            modifier = Modifier.padding(horizontal = 4.dp),
                            onClick = onToggleAgenticMode,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(if (agenticEnabled) "Agentic On" else "Agentic Mode")
                            }
                        }

                        IconButton(onClick = onToggleVoiceFeedback) {
                            Icon(
                                key = if (voiceEnabled) ChatAppIcons.Header.speakerOn else ChatAppIcons.Header.speakerOff,
                                contentDescription = "Toggle Voice Feedback",
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
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    Text("Send")
                                    Icon(
                                        modifier = Modifier.size(JewelTheme.iconButtonStyle.metrics.minSize.height),
                                        key = ChatAppIcons.Prompt.send,
                                        contentDescription = "Send",
                                        tint = if (promptInputState != MessageInputState.Disabled) ChatAppColors.Icon.enabledIconTint else ChatAppColors.Icon.disabledIconTint,
                                    )
                                }
                            },
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
                                contentDescription = "Toggle Microphone",
                            )
                        }

                        OutlinedButton(
                            modifier = Modifier.padding(horizontal = 4.dp),
                            onClick = onToggleAgenticMode,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(if (agenticEnabled) "Agentic On" else "Agentic Mode")
                            }
                        }

                        IconButton(onClick = {
                            localVoiceEnabled = !localVoiceEnabled
                            onToggleVoiceFeedback()
                        }) {
                            Icon(
                                key = if (localVoiceEnabled) ChatAppIcons.Header.speakerOn else ChatAppIcons.Header.speakerOff,
                                contentDescription = "Toggle Voice Feedback",
                            )
                        }

                        OutlinedButton(
                            modifier = Modifier.wrapContentSize(),
                            onClick = { onStop(textFieldState.text.toString()) },
                            content = {
                                Row(
                                    Modifier.padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    Text("Stop")
                                    Icon(
                                        modifier = Modifier.size(JewelTheme.iconButtonStyle.metrics.minSize.height),
                                        key = ChatAppIcons.Prompt.stop,
                                        contentDescription = "Stop sending",
                                        tint = ChatAppColors.Icon.stopIconTint,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
