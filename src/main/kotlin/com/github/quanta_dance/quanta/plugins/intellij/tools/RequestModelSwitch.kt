package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.openai.models.ChatModel

@JsonClassDescription("Request to switch the conversation model tier. Returns approval decision and the clamped model.")
class RequestModelSwitch : ToolInterface<Map<String, Any>> {

    @JsonPropertyDescription("Requested target model id, e.g., gpt-5-mini or gpt-5-nano")
    var desiredModel: String? = null

    @JsonPropertyDescription("Optional human-readable reason for the request")
    var reason: String? = null

    @JsonPropertyDescription("Optional hint: 'upgrade' or 'downgrade'")
    var direction: String? = null

    @JsonPropertyDescription("Optional: current model id the agent is running on. Including this helps the agent decide whether to request upgrade/downgrade.")
    var currentModel: String? = null

    override fun execute(project: Project): Map<String, Any> {
        val settings = QuantaAISettingsState.instance.state
        val dynamicEnabled = settings.dynamicModelEnabled ?: true
        // Use aiChatModel as the maximum allowed model (cap)
        val maxModel = settings.aiChatModel.ifBlank { ChatModel.GPT_5_MINI.toString() }
        val requestedRaw = (desiredModel ?: settings.aiChatModel).ifBlank { settings.aiChatModel }

        fun rank(id: String): Int {
            val s = id.lowercase()
            return when {
                s.contains("nano") -> 0
                s.contains("mini") -> 1
                s.contains("gpt-5") -> 2 // full gpt-5
                else -> 1 // default to middle tier if unknown
            }
        }
        fun normalize(id: String): String {
            val s = id.lowercase()
            return when {
                s.contains("nano") -> ChatModel.GPT_5_NANO.toString()
                s.contains("mini") -> ChatModel.GPT_5_MINI.toString()
                s.contains("gpt-5") -> ChatModel.GPT_5.toString()
                else -> ChatModel.GPT_5_MINI.toString()
            }
        }

        // Prefer provided currentModel for decision context if available, otherwise assume settings default
        val runtimeModel = currentModel?.takeIf { it.isNotBlank() } ?: settings.aiChatModel
        val normRequested = normalize(requestedRaw)
        val normMax = normalize(maxModel)
        val normCurrent = normalize(runtimeModel)

        val approved: Boolean
        val chosen: String
        val msg: String

        if (!dynamicEnabled) {
            approved = false
            chosen = normMax
            msg = "Dynamic model switching is disabled in settings. No switch will be made. Current: $normCurrent"
        } else {
            // compute candidate clamped to max cap
            val capped = if (rank(normRequested) <= rank(normMax)) normRequested else normMax

            if (rank(normRequested) > rank(normMax)) {
                // Requested model is above cap: deny upgrade beyond cap
                approved = false
                chosen = normMax
                msg = "Requested model ($normRequested) exceeds configured cap ($normMax); upgrade denied. Current: $normCurrent"
            } else {
                // within cap: approve
                approved = true
                chosen = capped
                msg = when (direction?.lowercase()) {
                    "upgrade" -> "Approved upgrade to $chosen from $normCurrent"
                    "downgrade" -> "Approved downgrade to $chosen from $normCurrent"
                    else -> "Approved model change to $chosen from $normCurrent"
                }
            }
        }

        try {
            project.service<ToolWindowService>().addToolingMessage(
                "Model switch request",
                "runtime=$normCurrent requested=$normRequested cap=$normMax -> approved=$approved new=$chosen reason=${reason.orEmpty()}"
            )
        } catch (_: Throwable) {}

        return mapOf(
            "approved" to approved,
            "newModel" to chosen,
            "reason" to (reason ?: msg),
            "currentModel" to normCurrent
        )
    }
}
