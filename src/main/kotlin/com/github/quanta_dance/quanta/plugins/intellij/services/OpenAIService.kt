// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.mcp.DynamicMcpToolProvider
import com.github.quanta_dance.quanta.plugins.intellij.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.project.CurrentFileContextProvider
import com.github.quanta_dance.quanta.plugins.intellij.services.openai.DefaultToolInvoker
import com.github.quanta_dance.quanta.plugins.intellij.services.openai.OpenAIClientProvider
import com.github.quanta_dance.quanta.plugins.intellij.services.openai.ToolInvoker
import com.github.quanta_dance.quanta.plugins.intellij.settings.Instructions
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsListener
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolsRegistry.toolsFor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import com.openai.core.MultipartField
import com.openai.models.ChatModel
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.audio.AudioModel
import com.openai.models.audio.speech.SpeechCreateParams
import com.openai.models.audio.speech.SpeechModel
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import com.openai.models.images.ImageGenerateParams
import com.openai.models.images.ImageModel
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.StructuredResponse
import com.openai.models.responses.StructuredResponseCreateParams
import com.openai.models.responses.Tool
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

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

    private var currentModel: String = run {
        val s = QuantaAISettingsState.instance.state
        val dynamic = s.dynamicModelEnabled == true
        val maxModel = s.aiChatModel.ifBlank { ChatModel.GPT_5_MINI.toString() }
        val initial = if (dynamic) ChatModel.GPT_5_MINI.toString() else maxModel
        normalize(initial)
    }

    private val managerLabel: String = "AI(manager)"

    @Volatile private var lastCtxHash: Int? = null
    @Volatile private var cachedMcpSignature: String? = null
    @Volatile private var cachedMcpTools: List<Tool> = emptyList()

    private fun rank(id: String): Int {
        val s = id.lowercase()
        return when {
            s.contains("nano") -> 0
            s.contains("mini") -> 1
            s.contains("gpt-5") -> 2
            else -> 1
        }
    }

    private fun normalize(id: String): String {
        val cm = ChatModel.of(id)
        return try { cm.validate(); cm.toString() } catch (_: Throwable) { ChatModel.GPT_5_MINI.toString() }
    }

    private fun clampToMax(requested: String, max: String): String {
        val r = normalize(requested); val m = normalize(max)
        return if (rank(r) <= rank(m)) r else m
    }

    private fun mergedInstructions(): String {
        val base = Instructions.instructions
        val extra = QuantaAISettingsState.instance.state.extraInstructions?.trim().orEmpty()
        return if (extra.isNotEmpty()) base + "\n\n# User Custom Instructions\n" + extra else base
    }

    private fun getEffectiveModel(): String {
        val settings = QuantaAISettingsState.instance.state
        val maxModel = settings.aiChatModel.ifBlank { ChatModel.GPT_5_MINI.toString() }
        return if (settings.dynamicModelEnabled == true) clampToMax(currentModel, maxModel) else normalize(maxModel)
    }

    private fun computeMcpSignature(mcp: McpClientService): String {
        val servers = mcp.listServers()
        val sb = StringBuilder()
        servers.sorted().forEach { s ->
            sb.append(s).append(':')
            val names = mcp.getTools(s).map { it.name }.sorted()
            names.forEach { n -> sb.append(n).append(',') }
            sb.append('|')
        }
        return sb.toString()
    }

    private fun getMcpToolsCached(): List<Tool> {
        val mcp = project.service<McpClientService>()
        return try {
            val sig = computeMcpSignature(mcp)
            if (sig != cachedMcpSignature) {
                val tools = DynamicMcpToolProvider.buildTools(mcp)
                cachedMcpTools = tools
                cachedMcpSignature = sig
            }
            cachedMcpTools
        } catch (_: Throwable) { emptyList() }
    }

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
                            val dynamic = newState.dynamicModelEnabled == true
                            val maxModel = newState.aiChatModel.ifBlank { ChatModel.GPT_5_MINI.toString() }
                            currentModel = normalize(if (dynamic) ChatModel.GPT_5_MINI.toString() else maxModel)
                            modelKey = newModelKey
                        }
                    } catch (_: Throwable) {}
                }
            },
        )
    }

    override fun dispose() { }

    private fun userMessage(text: String): ResponseInputItem =
        ResponseInputItem.ofMessage(
            ResponseInputItem.Message.builder().addInputTextContent(text).role(ResponseInputItem.Message.Role.USER).build(),
        )

    private fun systemMessage(text: String): ResponseInputItem =
        ResponseInputItem.ofMessage(
            ResponseInputItem.Message.builder().addInputTextContent(text).role(ResponseInputItem.Message.Role.SYSTEM).build(),
        )

    fun speech(message: String, consumer: (InputStream) -> Unit): CompletableFuture<Void> {
        val params = SpeechCreateParams.builder().input(message).model(SpeechModel.GPT_4O_MINI_TTS).voice(SpeechCreateParams.Voice.ASH).responseFormat(SpeechCreateParams.ResponseFormat.MP3).build()
        return oAI.async().audio().speech().create(params).thenAcceptAsync { response ->
            val inp = BufferedInputStream(response.body())
            consumer(inp)
        }
    }

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

    private fun buildToolErrorPayload(toolName: String, code: String = "tool_error", hint: String? = null): Map<String, Any> {
        val base = mutableMapOf<String, Any>("status" to "error", "tool" to toolName, "code" to code)
        if (hint != null) base["hint"] = hint
        return base
    }

    fun newSession(): String {
        thisLogger().info("Starting new AI session. Previous session: $currentSessionId")
        val old = currentSessionId
        currentSessionId = UUID.randomUUID().toString()
        lastResponseId = null
        QuantaAISettingsState.instance.state.mainLastResponseId = null
        lastCtxHash = null
        pcs.firePropertyChange("session", old, currentSessionId)
        project.service<ToolWindowService>().clear()
        return currentSessionId
    }

    fun getCurrentSessionId(): String = currentSessionId
    fun stopAndClearSession() { stopProcessing(); newSession() }

    private fun addSelectedBuiltInTools(
        builder: StructuredResponseCreateParams.Builder<OpenAIResponse>,
        allowedBuiltInNames: Set<String>?,
    ) {
        val all = toolsFor(project)
        val filtered =
            if (allowedBuiltInNames == null || allowedBuiltInNames.isEmpty()) {
                all.filter { cls -> cls.simpleName == "ListToolsCatalogTool" || cls.simpleName == "SetToolScopeTool" }
            } else {
                all.filter { cls -> allowedBuiltInNames.contains(cls.simpleName) || cls.simpleName == "ListToolsCatalogTool" || cls.simpleName == "SetToolScopeTool" }
            }
        filtered.forEach { builder.addTool(it) }
    }

    private fun addSelectedMcpTools(
        builder: StructuredResponseCreateParams.Builder<OpenAIResponse>,
        allowedMcpNames: Set<String>?, // server.method
    ) {
        if (allowedMcpNames == null || allowedMcpNames.isEmpty()) return
        val mcp = project.service<McpClientService>()
        val dyn = DynamicMcpToolProvider
        val tools = dyn.buildTools(mcp)
        tools.forEach { t ->
            try {
                val fn = t.asFunction().name()
                val pair = dyn.resolve(fn)
                if (pair != null) {
                    val name = pair.first + "." + pair.second
                    if (allowedMcpNames.contains(name)) builder.addTool(t)
                }
            } catch (_: Throwable) {}
        }
    }

    fun createParamsBuilder(
        inputs: MutableList<ResponseInputItem>,
        previousId: String?,
        overrideInstructions: String? = null,
        overrideModel: String? = null,
        allowedToolClassFilter: ((Class<*>) -> Boolean)? = null,
        includeMcp: Boolean = true,
        allowedBuiltInNames: Set<String>? = null,
        allowedMcpNames: Set<String>? = null,
    ): StructuredResponseCreateParams.Builder<OpenAIResponse> {
        val effectiveModel = overrideModel?.let { normalize(it) } ?: getEffectiveModel()
        val builder = ResponseCreateParams.builder()
            .instructions(overrideInstructions ?: mergedInstructions())
            .inputOfResponse(inputs)
            .reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).build())
            .maxOutputTokens(QuantaAISettingsState.instance.state.maxTokens)
            .text(OpenAIResponse::class.java)
            .model(ChatModel.of(effectiveModel))
        if (!previousId.isNullOrBlank()) builder.previousResponseId(previousId)

        addSelectedBuiltInTools(builder, allowedBuiltInNames)
        if (includeMcp) addSelectedMcpTools(builder, allowedMcpNames)
        return builder
    }

    private class DelayedSpinner(private val svc: ToolWindowService) {
        private val shown = AtomicBoolean(false)
        private var handle: ToolWindowService.SpinnerHandle? = null
        private var timer: Timer? = null
        fun startWithDelay(title: String, delayMs: Long = 300) {
            timer = Timer("delayed-spinner", true).apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        if (shown.compareAndSet(false, true)) handle = svc.startSpinner(title)
                    }
                }, delayMs)
            }
        }
        fun stopSuccess() { timer?.cancel(); handle?.stopSuccess() }
        fun stopError(msg: String) { timer?.cancel(); handle?.stopError(msg) }
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
            createParamsBuilder(
                inputs,
                previousId,
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
                        val functionCall: ResponseFunctionToolCall = item.asFunctionCall()
                        val callId = functionCall.callId()
                        if (!processedCallIds.add(callId)) return@map
                        project.service<ToolWindowService>().addToolingMessage(agentLabel, "Calling tool: ${functionCall.name()}")
                        try {
                            val functionResult = routeFunction(functionCall)
                            pendingToolOutputs.add(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder().callId(callId).outputAsJson(functionResult).build()))
                        } catch (_: Throwable) {
                            val errorPayload = buildToolErrorPayload(functionCall.name(), code = "unhandled_exception")
                            pendingToolOutputs.add(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder().callId(callId).outputAsJson(errorPayload).build()))
                        }
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
            val requestInputs = Collections.synchronizedList(mutableListOf<ResponseInputItem>())

            val ctx = CurrentFileContextProvider(project).getCurrent()
            if (ctx != null) {
                val header = "Current file open: ${ctx.filePathRelative}, file version: ${ctx.version} - you must always reread file if version changed"
                val caretLine = ctx.caretLine; val caretCol = ctx.caretColumn
                val sb = StringBuilder().append(header)
                if (caretLine != null && caretCol != null) sb.append("\nUser Caret position in the file ${ctx.filePathRelative} - Line: $caretLine, Column (Offset): $caretCol")
                if (ctx.selectedText != null && ctx.selectionStartLine != null && ctx.selectionStartColumn != null && ctx.selectionEndLine != null && ctx.selectionEndColumn != null) {
                    sb.append("\nSelection starts at line ${ctx.selectionStartLine}, column ${ctx.selectionStartColumn} and ends at line ${ctx.selectionEndLine}, column ${ctx.selectionEndColumn}\n")
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
                val effectiveForThisCall = getEffectiveModel()
                requestInputs.add(systemMessage("{\"currentModel\":\"${effectiveForThisCall}\"}"))
            } catch (_: Throwable) {}
            requestInputs.add(userMessage(text))

            var reprocess = true
            var spokeThisTurn = false
            val processedCallIds = mutableSetOf<String>()
            var previousIdForThisTurn = lastResponseId

            val tws = project.service<ToolWindowService>()
            val delayedSpinner = DelayedSpinner(tws)
            delayedSpinner.startWithDelay("AI is thinking [${getEffectiveModel()}]", 300)

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
                    val pendingToolOutputs = mutableListOf<ResponseInputItem>()

                    structResponse.output().map { item ->
                        when {
                            item.isReasoning() -> {
                                val reasoning = item.asReasoning()
                                reasoning.summary().forEach { summary -> project.service<ToolWindowService>().addToolingMessage("Reasoning(manager)", summary.text()) }
                            }
                            item.isFunctionCall() -> {
                                val functionCall: ResponseFunctionToolCall = item.asFunctionCall()
                                val callId = functionCall.callId()
                                if (!processedCallIds.add(callId)) return@map
                                project.service<ToolWindowService>().addToolingMessage(managerLabel, "Calling tool: ${functionCall.name()}")
                                try {
                                    val functionResult = routeFunction(functionCall)
                                    pendingToolOutputs.add(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder().callId(callId).outputAsJson(functionResult).build()))
                                } catch (_: Throwable) {
                                    val errorPayload = buildToolErrorPayload(functionCall.name(), code = "unhandled_exception")
                                    pendingToolOutputs.add(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder().callId(callId).outputAsJson(errorPayload).build()))
                                }
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
                    showNotification(project, e.message.orEmpty(), NotificationType.ERROR)
                    break
                }
            }
            lastResponseId = previousIdForThisTurn
            QuantaAISettingsState.instance.state.mainLastResponseId = lastResponseId
            operationInProgress = false
            pcs.firePropertyChange("inProgress", true, false)
        }
    }

    private fun routeFunction(functionCall: ResponseFunctionToolCall): Any {
        val name = functionCall.name()
        DynamicMcpToolProvider.resolve(name)?.let { (server, method) ->
            val argsJson = functionCall.arguments()
            val argsMap: Map<String, Any?> = try {
                mapper.readValue(argsJson, object : TypeReference<Map<String, Any?>>() {})
            } catch (_: Throwable) { emptyMap() }
            val out = project.service<McpClientService>().invokeTool(server, method, argsMap, null)
            return mapOf("output" to out)
        }
        if (name.contains('.')) {
            val idx = name.indexOf('.')
            val server = name.substring(0, idx)
            val method = name.substring(idx + 1)
            val argsJson = functionCall.arguments()
            val argsMap: Map<String, Any?> = try {
                mapper.readValue(argsJson, object : TypeReference<Map<String, Any?>>() {})
            } catch (_: Throwable) { emptyMap() }
            val out = project.service<McpClientService>().invokeTool(server, method, argsMap, null)
            return mapOf("output" to out)
        }
        return callFunction(functionCall)
    }

    private fun showNotification(project: Project?, content: String, type: NotificationType = NotificationType.INFORMATION) {
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Plugin Notifications")
        val notification = notificationGroup.createNotification(content, type)
        notification.notify(project)
    }

    fun transcript(inputStream: InputStream): String {
        return try { transcriptAsync(inputStream).get() } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw RuntimeException("Transcription was interrupted", e)
        } catch (e: Exception) { throw RuntimeException("Failed to transcribe audio", e) }
    }

    fun transcriptAsync(inputStream: InputStream): CompletableFuture<String> {
        val mf = MultipartField.builder<InputStream>().value(inputStream).contentType("audio/wav").filename("audio.wav").build()
        val params = TranscriptionCreateParams.builder().file(mf).model(AudioModel.WHISPER_1).build()
        return oAI.async().audio().transcriptions().create(params).thenApply { response -> response.asTranscription().text() }
    }

    fun transcriptStreaming(
        inputStream: InputStream,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
    ): CompletableFuture<Void?> {
        val mf = MultipartField.builder<InputStream>().value(inputStream).contentType("audio/wav").filename("audio.wav").build()
        val params = TranscriptionCreateParams.builder().file(mf).model(AudioModel.WHISPER_1).build()
        val response = oAI.async().audio().transcriptions().createStreaming(params)
        response.subscribe { event ->
            if (event.isTranscriptTextDelta()) onDelta(event.asTranscriptTextDelta().delta())
            else if (event.isTranscriptTextDone()) onDone(event.asTranscriptTextDone().text())
        }
        return response.onCompleteFuture()
    }

    private fun callFunction(functionCall: ResponseFunctionToolCall): Any {
        thisLogger().debug("Calling ${functionCall.name()}")
        return try { toolInvoker.invoke(project, functionCall) } catch (e: Throwable) {
            thisLogger().error(e.message, e)
            showNotification(project, e.message.orEmpty(), NotificationType.ERROR)
            buildToolErrorPayload(functionCall.name(), code = "unhandled_exception")
        }
    }

    fun generateImage(promptText: String): String {
        val params = ImageGenerateParams.builder().prompt(promptText).size(ImageGenerateParams.Size._1024X1024).model(ImageModel.DALL_E_3).build()
        return oAI.images().generate(params).data().orElseThrow().stream().flatMap { image -> image.url().stream() }.findFirst().orElseThrow()
    }
}
