package com.github.quanta_dance.quanta.plugins.intellij.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.mcp.DynamicMcpToolProvider
import com.github.quanta_dance.quanta.plugins.intellij.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.project.CurrentFileContextProvider
import com.github.quanta_dance.quanta.plugins.intellij.services.openai.DefaultToolInvoker
import com.github.quanta_dance.quanta.plugins.intellij.services.openai.OpenAIClientProvider
import com.github.quanta_dance.quanta.plugins.intellij.services.openai.ToolInvoker
import com.github.quanta_dance.quanta.plugins.intellij.settings.Instructions
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolsRegistry.toolsFor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
import com.openai.models.responses.*
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

@Service(Service.Level.PROJECT)
class OpenAIService(private val project: Project) {

    private var processingFuture: Future<*>? = null
    private var operationInProgress = false
    private val pcs = PropertyChangeSupport(this)

    private val oAI: OpenAIClient = OpenAIClientProvider.get(project)
    private val inputs: MutableList<ResponseInputItem> = Collections.synchronizedList(ArrayList<ResponseInputItem>())

    // New: session identifier for the current dialog. Generated on init and whenever a new session is started.
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

    private fun rank(id: String): Int {
        val s = id.lowercase(); return when {
            s.contains("nano") -> 0; s.contains("mini") -> 1; s.contains("gpt-5") -> 2; else -> 1
        }
    }

    private fun normalize(id: String): String {
        val cm = ChatModel.of(id)
        try {
            cm.validate()
            return cm.toString()
        } catch (_: Throwable) {
            return ChatModel.GPT_5_MINI.toString()
        }
    }

    private fun clampToMax(requested: String, max: String): String {
        val r = normalize(requested)
        val m = normalize(max)
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

    private fun createParamsBuilder(inputs: MutableList<ResponseInputItem>): StructuredResponseCreateParams.Builder<OpenAIResponse> {
        val effectiveModel = getEffectiveModel()
        val builder = ResponseCreateParams.builder()
            .instructions(mergedInstructions())
            .inputOfResponse(inputs)
            .reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).build())
            .maxOutputTokens(QuantaAISettingsState.instance.state.maxTokens)
            .text(OpenAIResponse::class.java)
            .model(ChatModel.of(effectiveModel))
        toolsFor(project).forEach { tool -> builder.addTool(tool) }
        val mcp = project.service<McpClientService>()
        DynamicMcpToolProvider.buildTools(mcp).forEach { t -> builder.addTool(t) }
        return builder
    }

    init {
        thisLogger().warn("AI Service initialized."); QDLog.info(thisLogger()) { "AI Service initialized." }
    }

    private fun userMessage(text: String): ResponseInputItem = ResponseInputItem.ofMessage(
        ResponseInputItem.Message.builder().addInputTextContent(text).role(ResponseInputItem.Message.Role.USER).build()
    )

    private fun systemMessage(text: String): ResponseInputItem = ResponseInputItem.ofMessage(
        ResponseInputItem.Message.builder().addInputTextContent(text).role(ResponseInputItem.Message.Role.SYSTEM)
            .build()
    )

    fun speech(message: String, consumer: (InputStream) -> Unit): CompletableFuture<Void> {
        val params = SpeechCreateParams.builder().input(message).model(SpeechModel.GPT_4O_MINI_TTS)
            .voice(SpeechCreateParams.Voice.ASH).responseFormat(SpeechCreateParams.ResponseFormat.MP3)
            .build(); return oAI.async().audio().speech().create(params)
            .thenAcceptAsync { response -> val inp = BufferedInputStream(response.body()); consumer(inp) }
    }

    fun inProgress(): Boolean = operationInProgress
    fun addPropertyChangeListener(listener: PropertyChangeListener) {
        pcs.addPropertyChangeListener(listener)
    }

    fun stopProcessing() {
        try {
            processingFuture?.run {
                if (!isDone) {
                    cancel(true); thisLogger().info("Processing was cancelled.")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); thisLogger().warn("Interrupted exception: ", e)
        } catch (e: Exception) {
            thisLogger().error("Error while stopping processing: ", e)
        }
    }

    private fun buildToolErrorPayload(
        toolName: String,
        code: String = "tool_error",
        hint: String? = null
    ): Map<String, Any> {
        val base = mutableMapOf<String, Any>(
            "status" to "error",
            "tool" to toolName,
            "code" to code
        ); if (hint != null) base["hint"] = hint; return base
    }

    /**
     * Public API: start a new session — clears the current dialog buffer (inputs), generates a new session id,
     * and notifies listeners so the UI can update. Returns the new session id.
     */
    fun newSession(): String {
        thisLogger().info("Starting new AI session. Previous session: $currentSessionId")
        inputs.clear()
        val old = currentSessionId
        currentSessionId = UUID.randomUUID().toString()
        pcs.firePropertyChange("session", old, currentSessionId)
        project.service<ToolWindowService>().clear()
        return currentSessionId
    }

    fun getCurrentSessionId(): String = currentSessionId

    fun stopAndClearSession() { // convenience for UI actions
        stopProcessing()
        newSession()
    }

    fun sendMessage(text: String, messageCallback: (OpenAIResponse) -> Unit = {}, toolCallback: () -> Unit = {}) {
        operationInProgress = true
        pcs.firePropertyChange("inProgress", false, true)
        processingFuture = ApplicationManager.getApplication().executeOnPooledThread {
            val ctx = CurrentFileContextProvider(project).getCurrent()
            if (ctx != null) {
                inputs.add(systemMessage("Current file open: ${ctx.filePathRelative}, file version: ${ctx.version} - you must always reread file if version changed"))
                val caretLine = ctx.caretLine;
                val caretCol = ctx.caretColumn;
                val sb = StringBuilder()
                if (caretLine != null && caretCol != null) sb.append("User Caret position in the file ${ctx.filePathRelative} - Line: $caretLine, Column (Offset): $caretCol") else sb.append(
                    "User Caret position in the file ${ctx.filePathRelative} - not available"
                )
                if (ctx.selectedText != null && ctx.selectionStartLine != null && ctx.selectionStartColumn != null && ctx.selectionEndLine != null && ctx.selectionEndColumn != null) {
                    sb.append("\nSelection starts at line ${ctx.selectionStartLine}, column ${ctx.selectionStartColumn} and ends at line ${ctx.selectionEndLine}, column ${ctx.selectionEndColumn}\n"); sb.append(
                        "Selected text is: ${ctx.selectedText}"
                    )
                }
                inputs.add(systemMessage(sb.toString()))
            }
            try {
                val effectiveForThisCall = getEffectiveModel()
                inputs.add(systemMessage("{\"currentModel\":\"${effectiveForThisCall}\"}"))
            } catch (_: Throwable) {
            }

            inputs.add(userMessage(text))

            var reprocess = true;
            var spokeThisTurn = false
            val processedCallIds = mutableSetOf<String>()
            while (reprocess) {
                reprocess = false
                var spinner: ToolWindowService.SpinnerHandle? = null
                try {
                    val effectiveForThisCall = getEffectiveModel()
                    spinner =
                        project.service<ToolWindowService>().startSpinner("AI is thinking [${effectiveForThisCall}]")
                    val createParams = createParamsBuilder(inputs).build()
                    try {
                        val payload = mapper.writeValueAsString(createParams); thisLogger().warn(
                            "OpenAI request payload: ${
                                payload.take(
                                    6000
                                )
                            }"
                        )
                    } catch (_: Throwable) {
                    }
                    val structResponse = oAI.responses().create(createParams)
                    spinner?.stopSuccess()
                    structResponse.output().map { item ->
                        when {
                            item.isReasoning() -> {
                                val reasoning = item.asReasoning(); reasoning.summary().forEach { summary ->
                                    project.service<ToolWindowService>().addToolingMessage("Reasoning", summary.text())
                                }; inputs.add(ResponseInputItem.ofReasoning(reasoning))
                            }

                            item.isFunctionCall() -> {
                                val functionCall: ResponseFunctionToolCall = item.asFunctionCall()
                                val callId = functionCall.callId()
                                if (!processedCallIds.add(callId)) {
                                    return@map
                                }
                                inputs.add(ResponseInputItem.ofFunctionCall(functionCall)); reprocess = true
                                try {
                                    val functionResult = routeFunction(functionCall)
                                    inputs.add(
                                        ResponseInputItem.ofFunctionCallOutput(
                                            ResponseInputItem.FunctionCallOutput.builder().callId(functionCall.callId())
                                                .outputAsJson(functionResult).build()
                                        )
                                    )
                                } catch (_: Throwable) {
                                    val errorPayload =
                                        buildToolErrorPayload(functionCall.name(), code = "unhandled_exception")
                                    inputs.add(
                                        ResponseInputItem.ofFunctionCallOutput(
                                            ResponseInputItem.FunctionCallOutput.builder().callId(functionCall.callId())
                                                .outputAsJson(errorPayload).build()
                                        )
                                    )
                                }
                            }

                            item.isMessage() -> {
                                item.message().map { m ->
                                    m.content().forEach { c ->
                                        val message = c.asOutputText(); project.service<ToolWindowService>()
                                        .addToolingMessage(
                                            "AI",
                                            message.summaryMessage
                                        ); message.ttsSummary?.also { summary ->
                                        if (!spokeThisTurn) {
                                            project.service<AIVoiceService>().say(summary); spokeThisTurn = true
                                        }
                                    }
                                    }
                                };
                                val ias =
                                    item.asMessage(); inputs.add(ResponseInputItem.ofResponseOutputMessage(ias.rawMessage))
                            }

                            item.isImageGenerationCall() -> { /* no-op */
                            }

                            else -> thisLogger().warn("Unknown item type received.")
                        }
                    }
                } catch (e: InterruptedException) {
                    spinner?.stopError("Cancelled after interruption"); thisLogger().warn(
                        "Execution interrupted: ",
                        e
                    ); Thread.currentThread().interrupt(); break
                } catch (e: Throwable) {
                    spinner?.stopError(e.message ?: "Unexpected error"); thisLogger().warn(
                        "Unexpected Error: ",
                        e
                    ); showNotification(project, e.message.orEmpty(), NotificationType.ERROR); break
                }
            }
            operationInProgress = false; pcs.firePropertyChange("inProgress", true, false)
        }
    }

    private fun routeFunction(functionCall: ResponseFunctionToolCall): Any {
        val name = functionCall.name()
        DynamicMcpToolProvider.resolve(name)?.let { (server, method) ->
            val argsJson = functionCall.arguments()
            val argsMap: Map<String, Any?> = try {
                @Suppress("UNCHECKED_CAST") mapper.readValue(argsJson, Map::class.java) as Map<String, Any?>
            } catch (_: Throwable) {
                emptyMap()
            }
            val out = project.service<McpClientService>().invokeTool(server, method, argsMap, null)
            return mapOf("output" to out)
        }
        if (name.contains('.')) {
            val idx = name.indexOf('.')
            val server = name.substring(0, idx)
            val method = name.substring(idx + 1)
            val argsJson = functionCall.arguments()
            val argsMap: Map<String, Any?> = try {
                @Suppress("UNCHECKED_CAST") mapper.readValue(argsJson, Map::class.java) as Map<String, Any?>
            } catch (_: Throwable) {
                emptyMap()
            }
            val out = project.service<McpClientService>().invokeTool(server, method, argsMap, null)
            return mapOf("output" to out)
        }
        return callFunction(functionCall)
    }

    private fun showNotification(
        project: Project?,
        content: String,
        type: NotificationType = NotificationType.INFORMATION
    ) {
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Plugin Notifications");
        val notification = notificationGroup.createNotification(content, type); notification.notify(project)
    }

    fun transcript(inputStream: InputStream): String {
        return try {
            transcriptAsync(inputStream).get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw RuntimeException("Transcription was interrupted", e)
        } catch (e: Exception) {
            throw RuntimeException("Failed to transcribe audio", e)
        }
    }

    fun transcriptAsync(inputStream: InputStream): CompletableFuture<String> {
        val mf = MultipartField.builder<InputStream>().value(inputStream).contentType("audio/wav").filename("audio.wav")
            .build();
        val params =
            TranscriptionCreateParams.builder().file(mf).model(AudioModel.WHISPER_1).build(); return oAI.async().audio()
            .transcriptions().create(params).thenApply { response -> response.asTranscription().text() }
    }

    fun transcriptStreaming(
        inputStream: InputStream,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit
    ): CompletableFuture<Void?> {
        val mf = MultipartField.builder<InputStream>().value(inputStream).contentType("audio/wav").filename("audio.wav")
            .build();
        val params = TranscriptionCreateParams.builder().file(mf).model(AudioModel.WHISPER_1).build();
        val response = oAI.async().audio().transcriptions().createStreaming(params); response.subscribe { event ->
            if (event.isTranscriptTextDelta()) {
                onDelta(event.asTranscriptTextDelta().delta())
            } else if (event.isTranscriptTextDone()) {
                onDone(event.asTranscriptTextDone().text())
            }
        }; return response.onCompleteFuture()
    }

    private fun callFunction(functionCall: ResponseFunctionToolCall): Any {
        thisLogger().debug("Calling ${functionCall.name()}"); return try {
            toolInvoker.invoke(project, functionCall)
        } catch (e: Throwable) {
            thisLogger().error(e.message, e); showNotification(
                project,
                e.message.orEmpty(),
                NotificationType.ERROR
            ); buildToolErrorPayload(functionCall.name(), code = "unhandled_exception")
        }
    }

    fun generateImage(promptText: String): String {
        val params = ImageGenerateParams.builder().prompt(promptText).size(ImageGenerateParams.Size._1024X1024)
            .model(ImageModel.DALL_E_3).build(); return oAI.images().generate(params).data().orElseThrow().stream()
            .flatMap { image -> image.url().stream() }.findFirst().orElseThrow()
    }
}
