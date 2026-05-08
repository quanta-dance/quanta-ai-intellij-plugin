package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto

fun FrontendQuantaSettingsState.State.toDto(): QuantaSettingsDto =
    QuantaSettingsDto(
        openAiUrl = openAiUrl,
        openAiToken = openAiToken,
        model = model,
        aiChatModel = aiChatModel,
        availableChatModels = availableChatModels,
        voiceEnabled = voiceEnabled,
        voiceByLocalTTS = voiceByLocalTTS,
        maxTokens = maxTokens,
        dynamicModelEnabled = dynamicModelEnabled,
        agenticEnabled = agenticEnabled,
        extraInstructions = extraInstructions,
        debugEnabled = debugEnabled,
        maxAutomaticTurns = maxAutomaticTurns,
        followEnabled = followEnabled,
        terminalToolEnabled = terminalToolEnabled,
        terminalAllowedCommandsCsv = terminalAllowedCommandsCsv,
        actionConfigsJson = actionConfigsJson,
    )

fun QuantaSettingsDto.toFrontendState(): FrontendQuantaSettingsState.State =
    FrontendQuantaSettingsState.State(
        openAiUrl = openAiUrl,
        openAiToken = openAiToken,
        model = model,
        aiChatModel = aiChatModel,
        availableChatModels = availableChatModels,
        voiceEnabled = voiceEnabled,
        voiceByLocalTTS = voiceByLocalTTS,
        maxTokens = maxTokens,
        dynamicModelEnabled = dynamicModelEnabled,
        agenticEnabled = agenticEnabled,
        extraInstructions = extraInstructions,
        debugEnabled = debugEnabled,
        maxAutomaticTurns = maxAutomaticTurns,
        followEnabled = followEnabled,
        terminalToolEnabled = terminalToolEnabled,
        terminalAllowedCommandsCsv = terminalAllowedCommandsCsv,
        actionConfigsJson = actionConfigsJson,
    )
