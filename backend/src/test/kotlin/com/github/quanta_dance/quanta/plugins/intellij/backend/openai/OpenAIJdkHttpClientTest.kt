// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.openai.core.RequestOptions
import com.openai.core.http.HttpMethod
import com.openai.core.http.HttpRequest
import com.openai.core.http.HttpRequestBody
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAIJdkHttpClientTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `execute forwards method headers query and body`() {
        val observed = mutableMapOf<String, String>()
        server =
            startServer { exchange ->
                observed["method"] = exchange.requestMethod
                observed["path"] = exchange.requestURI.path
                observed["query"] = exchange.requestURI.query.orEmpty()
                observed["header"] = exchange.requestHeaders.getFirst("X-Test").orEmpty()
                observed["contentType"] = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
                observed["body"] = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                exchange.respondJson(200, """{"ok":true}""")
            }

        val request =
            HttpRequest
                .builder()
                .method(HttpMethod.POST)
                .baseUrl(serverBaseUrl())
                .addPathSegment("responses")
                .putHeader("X-Test", "hello")
                .putHeader("Content-Type", "application/json")
                .putQueryParam("model", "gpt-test")
                .body(FixedHttpRequestBody("{\"input\":\"hi\"}"))
                .build()

        val response = OpenAIJdkHttpClient().execute(request, RequestOptions.none())
        response.use {
            assertEquals(200, it.statusCode())
            assertEquals("POST", observed["method"])
            assertEquals("/responses", observed["path"])
            assertTrue(observed["query"].orEmpty().contains("model=gpt-test"))
            assertEquals("hello", observed["header"])
            assertEquals("application/json", observed["contentType"])
            assertEquals("{\"input\":\"hi\"}", observed["body"])
        }
    }

    @Test
    fun `executeAsync returns response body and headers`() {
        server =
            startServer { exchange ->
                exchange.responseHeaders.add("X-Reply", "ok")
                exchange.respondJson(201, """{"id":"resp_123"}""")
            }

        val request =
            HttpRequest
                .builder()
                .method(HttpMethod.GET)
                .baseUrl(serverBaseUrl())
                .addPathSegment("responses")
                .build()

        val response = OpenAIJdkHttpClient().executeAsync(request, RequestOptions.none()).get()
        response.use {
            assertEquals(201, it.statusCode())
            assertEquals("ok", it.headers().values("X-Reply").first())
            assertEquals("{\"id\":\"resp_123\"}", it.body().readBytes().toString(StandardCharsets.UTF_8))
        }
    }

    private fun startServer(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { httpServer ->
            httpServer.createContext("/") { exchange ->
                handler(exchange)
            }
            httpServer.executor = Executors.newCachedThreadPool()
            httpServer.start()
        }

    private fun serverBaseUrl(): String {
        val port = requireNotNull(server).address.port
        return "http://127.0.0.1:$port"
    }
}

private class FixedHttpRequestBody(
    private val content: String,
) : HttpRequestBody {
    override fun writeTo(outputStream: java.io.OutputStream) {
        outputStream.write(content.toByteArray(StandardCharsets.UTF_8))
    }

    override fun contentType(): String = "application/json"

    override fun contentLength(): Long = content.toByteArray(StandardCharsets.UTF_8).size.toLong()

    override fun repeatable(): Boolean = true

    override fun close() = Unit
}

private fun HttpExchange.respondJson(
    statusCode: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(statusCode, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
