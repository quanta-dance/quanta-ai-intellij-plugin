// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatAppColors
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatAppIcons
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.MessageInputState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.isSending
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendSettingsSyncStateService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.ComboBox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.theme.iconButtonStyle

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
fun promptInput(
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
    settingsSyncState: FrontendSettingsSyncStateService.State = FrontendSettingsSyncStateService.State(),
    hasActiveAgentWork: Boolean = false,
    onInputChanged: (String) -> Unit = {},
    onSend: (String) -> Unit = {},
    onStop: (String) -> Unit = {},
    onStopAgents: () -> Unit = {},
    onSync: () -> Unit = {},
) {
    val isSending = promptInputState.isSending
    val syncStatus = settingsSyncState.status
    val isSettingsSyncing = syncStatus == FrontendSettingsSyncStateService.Status.SYNCING
    val isSettingsSyncFailed = syncStatus == FrontendSettingsSyncStateService.Status.FAILED
    val shouldShowAgentsStop =
        !isSettingsSyncing && !isSettingsSyncFailed && !isSending && hasActiveAgentWork && textFieldState.text.isBlank()
    var skipInputChangeUpdate by remember { mutableStateOf(false) }
    var localVoiceEnabled by remember { mutableStateOf(voiceEnabled) }
    var planHovered by remember { mutableStateOf(false) }
    var planPopupHovered by remember { mutableStateOf(false) }
    var planPinned by remember { mutableStateOf(false) }
    var planHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val displayedPlanStatus =
        currentPlanStatus.ifBlank { if (currentPlanText.isNotBlank()) "PLAN" else "NO PLAN" }
    val density = LocalDensity.current
    val planPopupPositionProvider =
        remember(density, currentPlanText) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val margin = with(density) { 12.dp.roundToPx() }
                    val x =
                        (anchorBounds.left + with(density) { 18.dp.roundToPx() })
                            .coerceIn(
                                margin,
                                (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin),
                            )

                    val preferredAbove = anchorBounds.top - popupContentSize.height - margin
                    val preferredBelow = anchorBounds.bottom + margin
                    val y =
                        when {
                            preferredAbove >= margin -> preferredAbove
                            preferredBelow + popupContentSize.height <= windowSize.height - margin -> preferredBelow
                            else -> (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
                        }
                    return IntOffset(x, y)
                }
            }
        }
    val planStatusIcon =
        when (displayedPlanStatus.uppercase()) {
            "DRAFT" -> ChatAppIcons.PlanStatus.draft
            "ACTIVE" -> ChatAppIcons.PlanStatus.active
            "DONE" -> ChatAppIcons.PlanStatus.done
            else -> ChatAppIcons.PlanStatus.noPlan
        }
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
            modifier =
                Modifier
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
                                when {
                                    isSending -> {
                                        onStop(message.toString())
                                    }

                                    isSettingsSyncFailed -> {
                                        onSync()
                                    }

                                    isSettingsSyncing -> {}

                                    message.isNotBlank() -> {
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
            modifier =
                Modifier
                    .weight(0.25f)
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(modifier = Modifier.padding(end = 8.dp)) {
                if ((planHovered || planPopupHovered || planPinned) && currentPlanText.isNotBlank()) {
                    Popup(
                        popupPositionProvider = planPopupPositionProvider,
                        properties = PopupProperties(focusable = false, clippingEnabled = false),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .widthIn(min = 360.dp, max = 860.dp)
                                    .heightIn(max = 720.dp)
                                    .pointerMoveFilter(
                                        onEnter = {
                                            planHideJob?.cancel()
                                            planPopupHovered = true
                                            false
                                        },
                                        onExit = {
                                            planHideJob?.cancel()
                                            planHideJob =
                                                scope.launch {
                                                    delay(160)
                                                    planPopupHovered = false
                                                    if (!planPinned) planHovered = false
                                                }
                                            false
                                        },
                                    ).background(Color(0xFF2B2B2B), RoundedCornerShape(6.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                Text(
                                    text = currentPlanText,
                                    style =
                                        JewelTheme.defaultTextStyle.copy(
                                            fontSize = 11.sp,
                                            color = Color.White,
                                        ),
                                )
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                .clickable {
                                    if (currentPlanText.isNotBlank()) {
                                        planPinned = !planPinned
                                        if (planPinned) {
                                            planHovered = true
                                        } else {
                                            planPopupHovered = false
                                            planHovered = false
                                        }
                                    }
                                }.padding(horizontal = 8.dp, vertical = 5.dp)
                                .pointerMoveFilter(
                                    onEnter = {
                                        planHideJob?.cancel()
                                        planHovered = true
                                        false
                                    },
                                    onExit = {
                                        planHideJob?.cancel()
                                        planHideJob =
                                            scope.launch {
                                                delay(160)
                                                if (!planPopupHovered && !planPinned) {
                                                    planHovered = false
                                                }
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
                            style =
                                JewelTheme.defaultTextStyle.copy(
                                    fontSize = 11.sp,
                                    color = Color.White,
                                ),
                        )
                    }

                    OutlinedButton(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        onClick = onToggleAgenticMode,
                    ) {
                        Text(if (agenticEnabled) "Agentic On" else "Agentic Mode")
                    }
                }
            }

            when {
                isSettingsSyncing || isSettingsSyncFailed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (availableModels.isNotEmpty()) {
                            key(currentModel) {
                                ComboBox(
                                    labelText = currentModel,
                                    modifier =
                                        Modifier
                                            .widthIn(min = 120.dp)
                                            .padding(end = 6.dp),
                                    popupContent = {
                                        Column {
                                            availableModels.forEach { model ->
                                                Text(
                                                    text = model,
                                                    modifier =
                                                        Modifier
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
                                key =
                                    when {
                                        micActive -> ChatAppIcons.Header.micActive
                                        micEnabled -> ChatAppIcons.Header.micOn
                                        else -> ChatAppIcons.Header.micOff
                                    },
                                contentDescription = "Toggle Microphone",
                            )
                        }

                        IconButton(onClick = onToggleVoiceFeedback) {
                            Icon(
                                key = if (voiceEnabled) ChatAppIcons.Header.speakerOn else ChatAppIcons.Header.speakerOff,
                                contentDescription = "Toggle Voice Feedback",
                            )
                        }

                        DefaultButton(
                            modifier = Modifier.wrapContentSize(),
                            enabled = isSettingsSyncFailed,
                            onClick = onSync,
                            content = {
                                Row(
                                    Modifier.padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    Text("Sync")
                                    Icon(
                                        modifier = Modifier.size(JewelTheme.iconButtonStyle.metrics.minSize.height),
                                        key = ChatAppIcons.Header.settings,
                                        contentDescription =
                                            if (isSettingsSyncFailed) {
                                                "Retry settings sync"
                                            } else {
                                                "Settings syncing"
                                            },
                                        tint =
                                            if (isSettingsSyncFailed) {
                                                ChatAppColors.Icon.enabledIconTint
                                            } else {
                                                ChatAppColors.Icon.disabledIconTint
                                            },
                                    )
                                }
                            },
                        )
                    }
                }

                promptInputState == MessageInputState.Disabled ||
                    promptInputState is MessageInputState.Enabled ||
                    promptInputState is MessageInputState.SendFailed ||
                    promptInputState is MessageInputState.Sent -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (availableModels.isNotEmpty()) {
                            key(currentModel) {
                                ComboBox(
                                    labelText =
                                    currentModel,
                                    modifier =
                                        Modifier
                                            .widthIn(min = 120.dp)
                                            .padding(end = 6.dp),
                                    popupContent = {
                                        Column {
                                            availableModels.forEach { model ->
                                                Text(
                                                    text = model,
                                                    modifier =
                                                        Modifier
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
                                key =
                                    when {
                                        micActive -> ChatAppIcons.Header.micActive
                                        micEnabled -> ChatAppIcons.Header.micOn
                                        else -> ChatAppIcons.Header.micOff
                                    },
                                contentDescription = "Toggle Microphone",
                            )
                        }

                        IconButton(onClick = onToggleVoiceFeedback) {
                            Icon(
                                key = if (voiceEnabled) ChatAppIcons.Header.speakerOn else ChatAppIcons.Header.speakerOff,
                                contentDescription = "Toggle Voice Feedback",
                            )
                        }

                        DefaultButton(
                            modifier = Modifier.wrapContentSize(),
                            enabled = shouldShowAgentsStop || promptInputState != MessageInputState.Disabled,
                            onClick = {
                                if (shouldShowAgentsStop) {
                                    onStopAgents()
                                } else {
                                    onSend(textFieldState.text.toString())
                                    skipInputChangeUpdate = true
                                    textFieldState.setTextAndPlaceCursorAtEnd("")
                                }
                            },
                            content = {
                                Row(
                                    Modifier.padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    Text(if (shouldShowAgentsStop) "Agents Stop" else "Send")
                                    Icon(
                                        modifier = Modifier.size(JewelTheme.iconButtonStyle.metrics.minSize.height),
                                        key = if (shouldShowAgentsStop) ChatAppIcons.Prompt.stop else ChatAppIcons.Prompt.send,
                                        contentDescription = if (shouldShowAgentsStop) "Stop agents" else "Send",
                                        tint =
                                            if (shouldShowAgentsStop || promptInputState != MessageInputState.Disabled) {
                                                ChatAppColors.Icon.enabledIconTint
                                            } else {
                                                ChatAppColors.Icon.disabledIconTint
                                            },
                                    )
                                }
                            },
                        )
                    }
                }

                promptInputState is MessageInputState.Sending -> {
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
                                                    modifier =
                                                        Modifier
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
                                key =
                                    when {
                                        micActive -> ChatAppIcons.Header.micActive
                                        micEnabled -> ChatAppIcons.Header.micOn
                                        else -> ChatAppIcons.Header.micOff
                                    },
                                contentDescription = "Toggle Microphone",
                            )
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
