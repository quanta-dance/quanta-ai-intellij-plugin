// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpOAuthAuthorizationProbeTest {
    @Test
    fun `uses configured authorization header and does not request oauth after successful response`() {
        val httpClient = mockk<HttpClient>()
        val response = mockk<HttpResponse<String>>()
        val request = slot<HttpRequest>()
        every { response.statusCode() } returns 200
        every { httpClient.send(capture(request), any<HttpResponse.BodyHandler<String>>()) } returns response

        val probe =
            McpOAuthService(httpClient = httpClient).probeAuthorization(
                resourceUrl = "https://mcp.example.test/api",
                headers = mapOf("Authorization" to "Bearer configured-token"),
            )

        assertEquals(200, probe.statusCode)
        assertNull(probe.challenge)
        assertEquals(
            "Bearer configured-token",
            request.captured
                .headers()
                .firstValue("Authorization")
                .orElse(null),
        )
    }

    @Test
    fun `only bearer challenge on unauthorized response produces oauth authorization challenge`() {
        val httpClient = mockk<HttpClient>()
        val response = mockk<HttpResponse<String>>()
        val request = slot<HttpRequest>()
        every { response.statusCode() } returns 401
        every { response.headers() } returns
            HttpHeaders.of(
                mapOf(
                    "WWW-Authenticate" to
                        listOf("Bearer resource_metadata=\"https://auth.example.test/resource\", scope=\"mcp\""),
                ),
            ) { _, _ -> true }
        every { httpClient.send(capture(request), any<HttpResponse.BodyHandler<String>>()) } returns response

        val probe =
            McpOAuthService(httpClient = httpClient).probeAuthorization(
                resourceUrl = "https://mcp.example.test/api",
                headers = null,
            )

        assertEquals(401, probe.statusCode)
        assertEquals(URI("https://auth.example.test/resource"), probe.challenge?.metadataUri)
        assertEquals(listOf("mcp"), probe.challenge?.scopes)
    }
}
