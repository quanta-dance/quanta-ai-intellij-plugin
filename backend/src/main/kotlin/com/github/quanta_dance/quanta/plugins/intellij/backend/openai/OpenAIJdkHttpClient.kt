// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.intellij.openapi.diagnostic.Logger
import com.openai.core.RequestOptions
import com.openai.core.http.Headers
import com.openai.core.http.HttpClient
import com.openai.core.http.HttpRequest
import com.openai.core.http.HttpRequestBody
import com.openai.core.http.HttpResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * JDK-based OpenAI transport used to avoid bundling the SDK's OkHttp adapter in the plugin runtime.
 */
class OpenAIJdkHttpClient : HttpClient {
    private val log = Logger.getInstance(OpenAIJdkHttpClient::class.java)

    override fun execute(
        request: HttpRequest,
        requestOptions: RequestOptions,
    ): HttpResponse {
        val requestBytes = request.body.readBytesAndClose()
        logRequest(request, requestBytes)
        return try {
            val connection = request.openConnection(requestOptions, requestBytes)
            val statusCode = connection.responseCode
            val responseBytes = connection.readResponseBytes()
            logResponse(statusCode, connection.headerFields, responseBytes)
            ByteArrayHttpResponse(
                statusCode = statusCode,
                headers = connection.headerFields.toOpenAiHeaders(),
                bodyBytes = responseBytes,
            )
        } catch (t: Throwable) {
            logFailure(request, t)
            throw t
        }
    }

    override fun executeAsync(
        request: HttpRequest,
        requestOptions: RequestOptions,
    ): CompletableFuture<HttpResponse> =
        CompletableFuture.supplyAsync {
            execute(request, requestOptions)
        }

    override fun close() = Unit

    private fun logRequest(
        request: HttpRequest,
        requestBytes: ByteArray,
    ) {
        val requestHeaders =
            request.headers.names().associateWith { name ->
                request.headers.values(name)
            }
//        val dumpFile =
//            runCatching {
//                Path.of(System.getProperty("user.dir") ?: ".")
//                    .toAbsolutePath()
//                    .normalize()
//                    .resolve("openai-request-dump.json")
//                    .also { path -> Files.writeString(path, requestBytes.decodeToStringPreview(1_000_000)) }
//            }.getOrNull()
        Path.of(System.getProperty("user.dir") ?: ".")
            .toAbsolutePath()
            .normalize()
            .resolve("openai-request-dump.json")
            .also { path -> Files.writeString(path, requestBytes.decodeToStringPreview(1_000_000)) }

        QDLog.info(log) {
            "OpenAIJdkHttpClient.execute ${
                request.method.name.uppercase(
                    Locale.ROOT,
                )
            } ${request.url()} bodyBytes=${requestBytes.size} headers=$requestHeaders bodyPreview=${
                requestBytes.decodeToStringPreview(
                    1_000_000,
                )
            } }"
        }
    }

    private fun logResponse(
        statusCode: Int,
        headerFields: Map<String?, List<String>>,
        responseBytes: ByteArray,
    ) {
        if (statusCode >= 400) {
            val body = responseBytes.decodeToStringFull()
            val line = "OpenAIJdkHttpClient response status=$statusCode headers=$headerFields body=$body"
            QDLog.warn(log) { line }
            println(line)
        } else {
            val line =
                "OpenAIJdkHttpClient response status=$statusCode headers=$headerFields bytes=${responseBytes.size}"
            QDLog.info(log) { line }
            println(line)
        }
    }

    private fun logFailure(
        request: HttpRequest,
        throwable: Throwable,
    ) {
        val line =
            "OpenAIJdkHttpClient failure method=${
                request.method.name.uppercase(
                    Locale.ROOT,
                )
            } url=${request.url()} error=${throwable::class.java.simpleName}: ${throwable.message}"
        QDLog.warn(log, { line }, throwable)
        println(line)
    }
}

private class ByteArrayHttpResponse(
    private val statusCode: Int,
    private val headers: Headers,
    private val bodyBytes: ByteArray,
) : HttpResponse {
    override fun statusCode(): Int = statusCode

    override fun headers(): Headers = headers

    override fun body(): InputStream = ByteArrayInputStream(bodyBytes)

    override fun close() = Unit
}

private fun HttpRequest.openConnection(
    requestOptions: RequestOptions,
    bodyBytes: ByteArray,
): HttpURLConnection {
    val connection =
        (URI.create(url()).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = requestOptions.connectTimeoutMillis()
            readTimeout = requestOptions.readTimeoutMillis()
            requestMethod = method.name.uppercase(Locale.ROOT)
        }

    headers.names().forEach { name ->
        if (name.equals("Content-Length", ignoreCase = true)) return@forEach
        headers.values(name).forEach { value ->
            connection.addRequestProperty(name, value)
        }
    }

    val timeout = requestOptions.timeout
    val readTimeoutSeconds = timeout?.read()?.seconds()?.toString()
    val requestTimeoutSeconds = timeout?.request()?.seconds()?.toString()
    if (!headers.names().contains("X-Stainless-Read-Timeout") && readTimeoutSeconds != null) {
        connection.addRequestProperty("X-Stainless-Read-Timeout", readTimeoutSeconds)
    }
    if (!headers.names().contains("X-Stainless-Timeout") && requestTimeoutSeconds != null) {
        connection.addRequestProperty("X-Stainless-Timeout", requestTimeoutSeconds)
    }

    if (body != null || requiresBody(method)) {
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bodyBytes.size)
        connection.outputStream.use { it.write(bodyBytes) }
    }

    return connection
}

private fun RequestOptions.connectTimeoutMillis(): Int =
    requestTimeout()?.connect()?.toMillis()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        ?: Duration.ofSeconds(30).toMillis().toInt()

private fun RequestOptions.readTimeoutMillis(): Int =
    requestTimeout()?.read()?.toMillis()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        ?: requestTimeout()?.request()?.toMillis()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        ?: Duration.ofMinutes(2).toMillis().toInt()

private fun RequestOptions.requestTimeout(): com.openai.core.Timeout? =
    runCatching {
        javaClass.getMethod("getTimeout").invoke(this) as? com.openai.core.Timeout
    }.getOrNull()

private fun requiresBody(method: com.openai.core.http.HttpMethod): Boolean =
    when (method) {
        com.openai.core.http.HttpMethod.POST,
        com.openai.core.http.HttpMethod.PUT,
        com.openai.core.http.HttpMethod.PATCH,
        -> true

        else -> false
    }

private fun Duration.seconds(): Long = this.seconds

private fun HttpRequestBody?.readBytesAndClose(): ByteArray {
    if (this == null) return ByteArray(0)
    return try {
        val output = ByteArrayOutputStream()
        writeTo(output)
        output.toByteArray()
    } finally {
        close()
    }
}

private fun Map<String?, List<String>>.toOpenAiHeaders(): Headers {
    val builder = Headers.builder()
    forEach { (name, values) ->
        if (name == null) return@forEach
        values.forEach { value ->
            builder.put(name, value)
        }
    }
    return builder.build()
}

private fun HttpURLConnection.readResponseBytes(): ByteArray {
    val stream = runCatching { inputStream }.getOrNull() ?: errorStream ?: return ByteArray(0)
    return stream.use { it.readBytes() }
}

private fun ByteArray.decodeToStringFull(): String =
    runCatching { decodeToString() }
        .getOrElse { "<non-text-body size=$size>" }
        .replace("\n", "\\n")

private fun ByteArray.decodeToStringPreview(maxChars: Int): String =
    decodeToStringFull().take(maxChars)
