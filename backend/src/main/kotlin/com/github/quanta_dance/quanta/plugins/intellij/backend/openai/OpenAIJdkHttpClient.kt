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
import java.io.FilterInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse

/**
 * JDK 11+ HTTP transport for the OpenAI SDK.
 * Uses java.net.http.HttpClient to avoid bundling okhttp, and streams the response body
 * directly so SSE events are delivered incrementally rather than buffered.
 */
class OpenAIJdkHttpClient internal constructor(
    private val timeouts: Timeouts = Timeouts(),
) : HttpClient {
    data class Timeouts(
        val connect: Duration = Duration.ofSeconds(10),
        val responseHeaders: Duration = Duration.ofSeconds(60),
        val responseIdle: Duration = Duration.ofSeconds(45),
        val responseTotal: Duration = Duration.ofSeconds(120),
    ) {
        init {
            require(!connect.isNegative && !connect.isZero) { "connect timeout must be positive" }
            require(!responseHeaders.isNegative && !responseHeaders.isZero) { "responseHeaders timeout must be positive" }
            require(!responseIdle.isNegative && !responseIdle.isZero) { "responseIdle timeout must be positive" }
            require(!responseTotal.isNegative && !responseTotal.isZero) { "responseTotal timeout must be positive" }
        }
    }

    private val log = Logger.getInstance(OpenAIJdkHttpClient::class.java)

    private val jdkClient =
        JdkHttpClient
            .newBuilder()
            .connectTimeout(timeouts.connect)
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
        private var maxRetries: Int = 1

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
    ): HttpResponse {
        val lifecycle = RequestLifecycle(request)
        return try {
            val preparedRequest = request.toJdkRequest(timeouts.responseHeaders)
            lifecycle.logStarted(preparedRequest.bodyBytes)
            val response = jdkClient.send(preparedRequest.request, JdkHttpResponse.BodyHandlers.ofInputStream())
            lifecycle.logHeaders(response.statusCode())
            val openAiResponse = response.toOpenAiResponse(lifecycle, timeouts)
            logResponse(request, response.statusCode(), openAiResponse.errorBodySnippet())
            openAiResponse
        } catch (t: Throwable) {
            lifecycle.logFailure(t)
            throw t
        }
    }

    override fun executeAsync(
        request: HttpRequest,
        requestOptions: RequestOptions,
    ): CompletableFuture<HttpResponse> {
        val lifecycle = RequestLifecycle(request)
        return try {
            val preparedRequest = request.toJdkRequest(timeouts.responseHeaders)
            lifecycle.logStarted(preparedRequest.bodyBytes)
            jdkClient
                .sendAsync(preparedRequest.request, JdkHttpResponse.BodyHandlers.ofInputStream())
                .thenApply<HttpResponse> { response ->
                    lifecycle.logHeaders(response.statusCode())
                    val openAiResponse = response.toOpenAiResponse(lifecycle, timeouts)
                    logResponse(request, response.statusCode(), openAiResponse.errorBodySnippet())
                    openAiResponse
                }.whenComplete { _, throwable ->
                    throwable?.let { lifecycle.logFailure(it.cause ?: it) }
                }
        } catch (t: Throwable) {
            lifecycle.logFailure(t)
            CompletableFuture.failedFuture(t)
        }
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
}

private data class PreparedJdkRequest(
    val request: JdkHttpRequest,
    val bodyBytes: Int,
)

private fun HttpRequest.toJdkRequest(responseHeadersTimeout: Duration): PreparedJdkRequest {
    val bodyBytes = body.readBytesAndClose()
    val contentType = body?.contentType() ?: "application/json"
    val hasBody = bodyBytes.isNotEmpty() || requiresBody(method)

    val builder =
        JdkHttpRequest
            .newBuilder()
            .uri(URI.create(url()))
            .timeout(responseHeadersTimeout)
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

    return PreparedJdkRequest(builder.build(), bodyBytes.size)
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

private fun JdkHttpResponse<InputStream>.toOpenAiResponse(
    lifecycle: RequestLifecycle,
    timeouts: OpenAIJdkHttpClient.Timeouts,
): OpenAiHttpResponse {
    val code = statusCode()
    val hdrs = headers().map().toOpenAiHeaders()
    val monitoredBody = DeadlineInputStream(body(), lifecycle, timeouts)
    // Error responses are buffered so the SDK's ErrorHandler can read the body reliably
    // and so we can log what OpenAI actually returned.
    // Success responses stream directly so SSE events are delivered incrementally.
    return if (code >= 400) {
        val bytes = monitoredBody.use { it.readBytes() }
        OpenAiHttpResponse(code, hdrs, ByteArrayInputStream(bytes), bytes)
    } else {
        OpenAiHttpResponse(code, hdrs, monitoredBody, null)
    }
}

private class RequestLifecycle(
    private val request: HttpRequest,
) {
    private val requestId = "oai-${requestSequence.incrementAndGet()}"
    val startedAtNanos = System.nanoTime()
    private val log = Logger.getInstance(OpenAIJdkHttpClient::class.java)

    fun logStarted(bodyBytes: Int) {
        QDLog.debug(log) {
            "OpenAI request id=$requestId ${request.method.name} ${request.url()} started; bodyBytes=$bodyBytes"
        }
    }

    fun logHeaders(statusCode: Int) {
        QDLog.info(log) {
            "OpenAI request id=$requestId headers received; status=$statusCode; elapsedMs=${elapsedMillis()}"
        }
    }

    fun logBodyClosed(bytesRead: Long) {
        QDLog.debug(log) {
            "OpenAI request id=$requestId response body closed; bytesRead=$bytesRead; totalMs=${elapsedMillis()}"
        }
    }

    fun logFailure(throwable: Throwable) {
        QDLog.warn(
            log,
            {
                "OpenAI request id=$requestId ${request.method.name} ${request.url()} failed after " +
                    "${elapsedMillis()}ms: ${throwable::class.java.simpleName}: ${throwable.message}"
            },
            throwable,
        )
    }

    fun elapsedMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

    companion object {
        private val requestSequence = AtomicLong()
    }
}

private class DeadlineInputStream(
    delegate: InputStream,
    private val lifecycle: RequestLifecycle,
    timeouts: OpenAIJdkHttpClient.Timeouts,
) : FilterInputStream(delegate) {
    private val closed = AtomicBoolean()
    private val startedAtNanos = lifecycle.startedAtNanos
    private val idleTimeoutNanos = timeouts.responseIdle.toNanos()
    private val totalTimeoutNanos = timeouts.responseTotal.toNanos()
    private val bytesRead = AtomicLong()

    @Volatile
    private var lastProgressAtNanos = startedAtNanos

    @Volatile
    private var timeout: SocketTimeoutException? = null

    private val watchdog: ScheduledFuture<*> =
        watchdogExecutor.scheduleAtFixedRate(
            ::closeOnExpiredDeadline,
            watchdogPeriodMillis(timeouts.responseIdle),
            watchdogPeriodMillis(timeouts.responseIdle),
            TimeUnit.MILLISECONDS,
        )

    override fun read(): Int = recordRead { super.read() }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = recordRead { super.read(buffer, offset, length) }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            watchdog.cancel(false)
            try {
                super.close()
            } finally {
                lifecycle.logBodyClosed(bytesRead.get())
            }
        }
    }

    private fun recordRead(read: () -> Int): Int {
        timeout?.let { throw it }
        return try {
            val count = read()
            if (count > 0) {
                bytesRead.addAndGet(count.toLong())
                lastProgressAtNanos = System.nanoTime()
            } else if (count == -1) {
                close()
            }
            timeout?.let { throw it }
            count
        } catch (exception: java.io.IOException) {
            timeout?.let { throw it }
            throw exception
        }
    }

    private fun closeOnExpiredDeadline() {
        if (closed.get()) return
        val now = System.nanoTime()
        val totalElapsed = now - startedAtNanos
        val idleElapsed = now - lastProgressAtNanos
        val reason =
            when {
                totalElapsed >= totalTimeoutNanos -> {
                    "response exceeded the ${
                        TimeUnit.NANOSECONDS.toSeconds(
                            totalTimeoutNanos,
                        )
                    }s total deadline"
                }

                idleElapsed >= idleTimeoutNanos -> {
                    "response was idle for ${
                        TimeUnit.NANOSECONDS.toSeconds(
                            idleTimeoutNanos,
                        )
                    }s"
                }

                else -> {
                    return
                }
            }
        val deadlineException = SocketTimeoutException("OpenAI $reason")
        timeout = deadlineException
        lifecycle.logFailure(deadlineException)
        close()
    }

    private fun watchdogPeriodMillis(idleTimeout: Duration): Long = (idleTimeout.toMillis() / 4).coerceIn(10, 1_000)

    companion object {
        private val watchdogExecutor =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "quanta-openai-response-watchdog").apply { isDaemon = true }
            }
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
