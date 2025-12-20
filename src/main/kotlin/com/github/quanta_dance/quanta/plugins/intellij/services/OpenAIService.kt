// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.project.CurrentFileContextProvider
import com.github.quanta_dance.quanta.plugins.intellij.services.openai.*
import com.github.quanta_dance.quanta.plugins.intellij.services.ui.DelayedSpinner
import com.github.quanta_dance.quanta.plugins.intellij.services.ui.Notifications
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsListener
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import com.openai.models.images.ImageGenerateParams
import com.openai.models.images.ImageModel
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.StructuredResponse
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.*
import java.util.concurrent.Future

@Service(Service.Level.PROJECT)
class OpenAIService(private val project: Project) : Disposable {
    private var processingFuture: Future<*>? = null
    private var operationInProgress = false
    private val pcs = PropertyChangeSupport(this)

    @Volatile private var oAI: OpenAIClient = OpenAIClientProvider.get(project)
    @Volatile private var clientKey: Pair<String, String> = QuantaAISettingsState.instance.state.let { it.host to it.token }
    @Volatile private var modelKey: Pair<Boolean, String> = QuantaAISettingsState.instance.state.let { (it.dynamicModelEnabled == true) to it.aiChatModel }

    private var lastResponseId: String? = QuantaAISettingsState.instance.state.mainLastResponseId
    private var currentSessionId: String = UUID.randomUUID().toString()

    private val toolInvoker: ToolInvoker = DefaultToolInvoker()
    private val mapper = ObjectMapper()
    private val toolRouter = ToolRouter(project, toolInvoker, mapper)
    private val responseBuilder = ResponseBuilder(project)

    private var currentModel: String = ModelSelector.initialModel()

    private val managerLabel: String = "AI(manager)"

    @Volatile private var lastCtxHash: Int? = null

    init {
        thisLogger().warn("AI Service initialized.")
        QDLog.info(thisLogger()) { "AI Service initialized." }
        project.messageBus.connect(this).subscribe(
            QuantaAISettingsListener.TOPIC,
            object : QuantaAISettingsListener {
                override fun onSettingsChanged(newState: QuantaAISettingsState.QuantaAIState) {
                    try {
                        val newClientKey = newState.host to newState.token
                        if (newClientKey != clientKey) {
                            oAI = OpenAIClientProvider.get(project)
                            clientKey = newClientKey
                            thisLogger().info("OpenAI client reinitialized after host/token change")
                        }
                        val newModelKey = (newState.dynamicModelEnabled == true) to newState.aiChatModel
                        if (newModelKey != modelKey) {
                            currentModel = ModelSelector.initialModel()
                            modelKey = newModelKey
                        }
                    } catch (_: Throwable) {}
                }
            },
        )
    }

    override fun dispose() { }

    private fun userMessage(text: String): ResponseInputItem =
        com.openai.models.responses.ResponseInputItem.ofMessage(
            com.openai.models.responses.ResponseInputItem.Message.builder().addInputTextContent(text).role(com.openai.models.responses.ResponseInputItem.Message.Role.USER).build(),
        )

    private fun systemMessage(text: String): ResponseInputItem =
        com.openai.models.responses.ResponseInputItem.ofMessage(
            com.openai.models.responses.ResponseInputItem.Message.builder().addInputTextContent(text).role(com.openai.models.responses.ResponseInputItem.Message.Role.SYSTEM).build(),
        )

    fun inProgress(): Boolean = operationInProgress
    fun addPropertyChangeListener(listener: PropertyChangeListener) = pcs.addPropertyChangeListener(listener)

    fun stopProcessing() {
        try {
            processingFuture?.run {
                if (!isDone) {
                    cancel(true)
                    thisLogger().info("Processing was cancelled.")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); thisLogger().warn("Interrupted exception: ", e)
        } catch (e: Exception) { thisLogger().error("Error while stopping processing: ", e) }
    }

    fun newSession(): String {
        thisLogger().info("Starting new AI session. Previous session: $currentSessionId")
        val old = currentSessionId
        currentSessionId = UUID.randomUUID().toString()
        lastResponseId = null
        QuantaAISettingsState.instance.state.mainLastResponseId = null
        lastCtxHash = null
        // Reset sub-agents conversation state so they start fresh with the new manager thread
        try { project.service<AgentManagerService>().resetForNewSession() } catch (_: Throwable) {}
        pcs.firePropertyChange("session", old, currentSessionId)
        project.service<ToolWindowService>().clear()
        return currentSessionId
    }

    fun getCurrentSessionId(): String = currentSessionId
    fun stopAndClearSession() { stopProcessing(); newSession() }

    private fun buildBootstrapContext(): String {
        val agents = try { project.service<AgentManagerService>().getAgentsSnapshot() } catch (_: Throwable) { emptyList() }
        val b = StringBuilder()
        b.append("New session bootstrap context.\n")
        b.append("Session ID: ").append(currentSessionId).append('\n')
        b.append("Existing sub-agents: ").append(agents.size).append('\n')
        agents.forEachIndexed { idx, a ->
            b.append(idx + 1).append('.').append(' ')
                .append("id=").append(a.id).append(", role=").append(a.role)
            a.model?.let { m -> b.append(", model=").append(m) }
            b.append('\n')
        }
        b.append("Use AgentSendMessageTool with agent_id to communicate with any sub-agent.\n")
        b.append("If needed, create or remove agents via AgentCreateTool / AgentRemoveTool.")
        return b.toString()
    }

    fun createResponse(
        inputs: MutableList<ResponseInputItem>,
        previousId: String?,
        overrideInstructions: String? = null,
        overrideModel: String? = null,
        allowedToolClassFilter: ((Class<*>) -> Boolean)? = null,
        includeMcp: Boolean = true,
        allowedBuiltInNames: Set<String>? = null,
        allowedMcpNames: Set<String>? = null,
    ): Pair<StructuredResponse<OpenAIResponse>, String?> {
        val createParams =
            responseBuilder.createParamsBuilder(
                inputs,
                previousId,
                currentModel,
                overrideInstructions,
                overrideModel,
                allowedToolClassFilter,
                includeMcp,
                allowedBuiltInNames,
                allowedMcpNames,
            ).build()
        val structResponse = oAI.responses().create(createParams)
        val id = try { structResponse.id() } catch (_: Throwable) { null }
        return structResponse to id
    }

    fun agentTurn(
        inputs: MutableList<ResponseInputItem>,
        previousId: String?,
        overrideInstructions: String? = null,
        overrideModel: String? = null,
        allowedToolClassFilter: ((Class<*>) -> Boolean)? = null,
        includeMcp: Boolean = true,
        agentLabel: String = "AI(agent)",
    ): Pair<String, String?> {
        var localPrevId = previousId
        val aggregated = StringBuilder()
        val processedCallIds = mutableSetOf<String>()
        var reprocess = true
        while (reprocess) {
            reprocess = false
            val (structResponse, newId) = createResponse(inputs, localPrevId, overrideInstructions, overrideModel, allowedToolClassFilter, includeMcp)
            localPrevId = newId
            inputs.clear()
            val pendingToolOutputs = mutableListOf<ResponseInputItem>()

            structResponse.output().map { item ->
                when {
                    item.isFunctionCall() -> {
                        val functionCall: com.openai.models.responses.ResponseFunctionToolCall = item.asFunctionCall()
                        val callId = functionCall.callId()
                        if (!processedCallIds.add(callId)) return@map
                        project.service<ToolWindowService>().addToolingMessage(agentLabel, "Calling tool: ${functionCall.name()}")
                        val functionResult = toolRouter.route(functionCall)
                        pendingToolOutputs.add(com.openai.models.responses.ResponseInputItem.ofFunctionCallOutput(com.openai.models.responses.ResponseInputItem.FunctionCallOutput.builder().callId(callId).outputAsJson(functionResult).build()))
                    }
                    item.isMessage() -> {
                        item.message().map { m -> m.content().forEach { c ->
                            val txt = c.asOutputText().summaryMessage
                            if (txt.isNotBlank()) project.service<ToolWindowService>().addToolingMessage(agentLabel, txt)
                            aggregated.append(txt).append('\n')
                        } }
                    }
                }
            }
            if (pendingToolOutputs.isNotEmpty()) { inputs.addAll(pendingToolOutputs); reprocess = true }
        }
        return aggregated.toString().trim() to localPrevId
    }

    fun sendMessage(
        text: String,
        messageCallback: (OpenAIResponse) -> Unit = {},
        toolCallback: () -> Unit = {},
    ) {
        operationInProgress = true
        pcs.firePropertyChange("inProgress", false, true)
        processingFuture = ApplicationManager.getApplication().executeOnPooledThread {
            val requestInputs = Collections.synchronizedList(mutableListOf<com.openai.models.responses.ResponseInputItem>())

            val ctx = CurrentFileContextProvider(project).getCurrent()
            if (ctx != null) {
                val header = "Current file open: ${ctx.filePathRelative}, file version: ${ctx.version} - you must always reread file if version changed"
                val caretLine = ctx.caretLine; val caretCol = ctx.caretColumn
                val sb = StringBuilder().append(header)
                if (caretLine != null && caretCol != null) sb.append("\nUser Caret position in the file ${ctx.filePathRelative} - Line: $caretLine, Column (Offset): $caretCol")
                if (ctx.selectedText != null && ctx.selectionStartLine != null && ctx.selectionStartColumn != null && ctx.selectionEndLine != null && ctx.selectionEndColumn != null) {
                    sb.append("\nSelection starts at line ${ctx.selectionStartLine}, column ${ctx.selectionStartColumn} and ends at line ${ctx.selectionEndLine}, column ${ctx.selectionEndLine}\n")
                    sb.append("Selected text is: ${ctx.selectedText}")
                }
                val payload = sb.toString()
                val h = payload.hashCode()
                if (lastCtxHash == null || lastCtxHash != h || lastResponseId == null) {
                    requestInputs.add(systemMessage(payload))
                    lastCtxHash = h
                }
            }
            try {
                val effectiveForThisCall = ModelSelector.effectiveModel(currentModel)
                requestInputs.add(systemMessage("{\"currentModel\":\"${effectiveForThisCall}\"}"))
            } catch (_: Throwable) {}
            // Inject a bootstrap summary on the first turn of a new session so the manager knows existing agents
            if (lastResponseId == null) {
                requestInputs.add(systemMessage(buildBootstrapContext()))
            }
            requestInputs.add(userMessage(text))

            var reprocess = true
            var spokeThisTurn = false
            val processedCallIds = mutableSetOf<String>()
            var previousIdForThisTurn = lastResponseId

            val tws = project.service<ToolWindowService>()
            val delayedSpinner = DelayedSpinner(tws)
            delayedSpinner.startWithDelay("AI is thinking [${ModelSelector.effectiveModel(currentModel)}]", 300)

            while (reprocess) {
                reprocess = false
                try {
                    val scopeSvc = project.service<ToolScopeService>()
                    val (stickyB, stickyM) = scopeSvc.getSticky()
                    val (turnB, turnM) = scopeSvc.consumeCurrent()
                    val allowedB = (stickyB + turnB).toSet()
                    val allowedM = (stickyM + turnM).toSet()

                    val (structResponse, newId) = createResponse(
                        requestInputs,
                        previousIdForThisTurn,
                        allowedToolClassFilter = null,
                        includeMcp = allowedM.isNotEmpty(),
                        allowedBuiltInNames = allowedB,
                        allowedMcpNames = allowedM,
                    )
                    previousIdForThisTurn = newId
                    delayedSpinner.stopSuccess()

                    requestInputs.clear()
                    val pendingToolOutputs = mutableListOf<com.openai.models.responses.ResponseInputItem>()

                    structResponse.output().map { item ->
                        when {
                            item.isReasoning() -> {
                                val reasoning = item.asReasoning()
                                reasoning.summary().forEach { summary -> project.service<ToolWindowService>().addToolingMessage("Reasoning(manager)", summary.text()) }
                            }
                            item.isFunctionCall() -> {
                                val functionCall: com.openai.models.responses.ResponseFunctionToolCall = item.asFunctionCall()
                                val callId = functionCall.callId()
                                if (!processedCallIds.add(callId)) return@map
                                project.service<ToolWindowService>().addToolingMessage(managerLabel, "Calling tool: ${functionCall.name()}")
                                val functionResult = toolRouter.route(functionCall)
                                pendingToolOutputs.add(com.openai.models.responses.ResponseInputItem.ofFunctionCallOutput(com.openai.models.responses.ResponseInputItem.FunctionCallOutput.builder().callId(callId).outputAsJson(functionResult).build()))
                            }
                            item.isMessage() -> {
                                item.message().map { m ->
                                    m.content().forEach { c ->
                                        val message = c.asOutputText()
                                        project.service<ToolWindowService>().addToolingMessage(managerLabel, message.summaryMessage)
                                        message.ttsSummary?.also { summary ->
                                            if (!spokeThisTurn) {
                                                project.service<AIVoiceService>().say(summary)
                                                spokeThisTurn = true
                                            }
                                        }
                                    }
                                }
                            }
                            item.isImageGenerationCall() -> { }
                            else -> thisLogger().warn("Unknown item type received.")
                        }
                    }

                    if (pendingToolOutputs.isNotEmpty()) { requestInputs.addAll(pendingToolOutputs); reprocess = true }
                } catch (e: InterruptedException) {
                    delayedSpinner.stopError("Cancelled after interruption")
                    thisLogger().warn("Execution interrupted: ", e)
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Throwable) {
                    delayedSpinner.stopError(e.message ?: "Unexpected error")
                    thisLogger().warn("Unexpected Error: ", e)
                    Notifications.show(project, e.message.orEmpty(), NotificationType.ERROR)
                    break
                }
            }
            lastResponseId = previousIdForThisTurn
            QuantaAISettingsState.instance.state.mainLastResponseId = lastResponseId
            operationInProgress = false
            pcs.firePropertyChange("inProgress", true, false)
        }
    }

    fun generateImage(promptText: String): String {
        val params = ImageGenerateParams.builder().prompt(promptText).size(ImageGenerateParams.Size._1024X1024).model(ImageModel.DALL_E_3).build()
        return oAI.images().generate(params).data().orElseThrow().stream().flatMap { image -> image.url().stream() }.findFirst().orElseThrow()
    }
}
