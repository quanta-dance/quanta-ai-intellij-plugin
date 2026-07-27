// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.intellij.openapi.diagnostic.Logger
import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientImpl
import com.openai.core.ClientOptions
import com.openai.core.RequestOptions
import com.openai.core.http.Headers
import com.openai.core.http.HttpClient
import com.openai.core.http.HttpMethod
import com.openai.core.http.HttpRequest
import com.openai.core.http.HttpRequestBody
import com.openai.core.http.HttpResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse

/**
 * JDK 11+ HTTP transport for the OpenAI SDK.
 * Uses java.net.http.HttpClient to avoid bundling okhttp, and streams the response body
 * directly so SSE events are delivered incrementally rather than buffered.
 */
class OpenAIJdkHttpClient : HttpClient {
    private val log = Logger.getInstance(OpenAIJdkHttpClient::class.java)

    private val jdkClient =
        JdkHttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(JdkHttpClient.Redirect.NORMAL)
            // Prefer HTTP/1.1 — HTTP/2 multiplexing can cause 502s with some proxies/gateways
            .version(JdkHttpClient.Version.HTTP_1_1)
            .build()

    companion object {
        fun builder() = Builder()
    }

    class Builder internal constructor() {
        private var apiKey: String = ""
        private var baseUrl: String? = null
        private var maxRetries: Int = 2

        fun apiKey(apiKey: String) = apply { this.apiKey = apiKey }

        fun baseUrl(baseUrl: String) = apply { this.baseUrl = baseUrl }

        fun maxRetries(maxRetries: Int) = apply { this.maxRetries = maxRetries }

        fun build(): OpenAIClient =
            OpenAIClientImpl(
                ClientOptions
                    .builder()
                    .httpClient(OpenAIJdkHttpClient())
                    .apiKey(apiKey)
                    .apply { baseUrl?.let { baseUrl(it) } }
                    .maxRetries(maxRetries)
                    .build(),
            )
    }

    override fun execute(
        request: HttpRequest,
        requestOptions: RequestOptions,
    ): HttpResponse =
        try {
            val jdkRequest = request.toJdkRequest()
            val response = jdkClient.send(jdkRequest, JdkHttpResponse.BodyHandlers.ofInputStream())
            val openAiResponse = response.toOpenAiResponse()
            logResponse(request, response.statusCode(), openAiResponse.errorBodySnippet())
            openAiResponse
        } catch (t: Throwable) {
            logFailure(request, t)
            throw t
        }

    override fun executeAsync(
        request: HttpRequest,
        requestOptions: RequestOptions,
    ): CompletableFuture<HttpResponse> =
        try {
            val jdkRequest = request.toJdkRequest()
            jdkClient
                .sendAsync(jdkRequest, JdkHttpResponse.BodyHandlers.ofInputStream())
                .thenApply { response ->
                    val openAiResponse = response.toOpenAiResponse()
                    logResponse(request, response.statusCode(), openAiResponse.errorBodySnippet())
                    openAiResponse
                }
        } catch (t: Throwable) {
            logFailure(request, t)
            CompletableFuture.failedFuture(t)
        }

    override fun close() = Unit

    private fun logResponse(
        request: HttpRequest,
        statusCode: Int,
        errorBody: String? = null,
    ) {
        if (statusCode >= 400) {
            QDLog.warn(log) {
                "OpenAI ${request.method.name} ${request.url()} → $statusCode" +
                    if (errorBody != null) " body=$errorBody" else ""
            }
        } else {
            QDLog.info(log) { "OpenAI ${request.method.name} ${request.url()} → $statusCode" }
        }
    }

    private fun logFailure(
        request: HttpRequest,
        t: Throwable,
    ) {
        QDLog.warn(
            log,
            { "OpenAI ${request.method.name} ${request.url()} failed: ${t::class.java.simpleName}: ${t.message}" },
            t,
        )
    }
}

private fun HttpRequest.toJdkRequest(): JdkHttpRequest {
    val bodyBytes = body.readBytesAndClose()
    val contentType = body?.contentType() ?: "application/json"
    val hasBody = bodyBytes.isNotEmpty() || requiresBody(method)

    val builder =
        JdkHttpRequest
            .newBuilder()
            .uri(URI.create(url()))
            .method(
                method.name.uppercase(),
                if (hasBody) {
                    JdkHttpRequest.BodyPublishers.ofByteArray(bodyBytes)
                } else {
                    JdkHttpRequest.BodyPublishers.noBody()
                },
            )

    headers.names().forEach { name ->
        if (name.equals("Content-Length", ignoreCase = true)) return@forEach
        headers.values(name).forEach { value -> builder.header(name, value) }
    }

    // Set Content-Type if the request has a body and the SDK didn't include it
    if (hasBody && headers.names().none { it.equals("Content-Type", ignoreCase = true) }) {
        builder.header("Content-Type", contentType)
    }

    return builder.build()
}

private class OpenAiHttpResponse(
    private val code: Int,
    private val hdrs: Headers,
    private val stream: InputStream,
    // Non-null only for error responses — pre-read so logging and ErrorHandler both see the body
    private val errorBytes: ByteArray?,
) : HttpResponse {
    override fun statusCode() = code

    override fun headers() = hdrs

    override fun body(): InputStream = stream

    override fun close() = stream.close()

    fun errorBodySnippet(): String? = errorBytes?.decodeToString()?.take(500)
}

private fun JdkHttpResponse<InputStream>.toOpenAiResponse(): OpenAiHttpResponse {
    val code = statusCode()
    val hdrs = headers().map().toOpenAiHeaders()
    // Error responses are buffered so the SDK's ErrorHandler can read the body reliably
    // and so we can log what OpenAI actually returned.
    // Success responses stream directly so SSE events are delivered incrementally.
    return if (code >= 400) {
        val bytes = body().use { it.readBytes() }
        OpenAiHttpResponse(code, hdrs, ByteArrayInputStream(bytes), bytes)
    } else {
        OpenAiHttpResponse(code, hdrs, body(), null)
    }
}

private fun Map<String, List<String>>.toOpenAiHeaders(): Headers {
    val builder = Headers.builder()
    forEach { (name, values) -> values.forEach { value -> builder.put(name, value) } }
    return builder.build()
}

private fun HttpRequestBody?.readBytesAndClose(): ByteArray {
    if (this == null) return ByteArray(0)
    return try {
        val out = ByteArrayOutputStream()
        writeTo(out)
        out.toByteArray()
    } finally {
        close()
    }
}

private fun requiresBody(method: HttpMethod): Boolean =
    when (method) {
        HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH -> true
        else -> false
    }
