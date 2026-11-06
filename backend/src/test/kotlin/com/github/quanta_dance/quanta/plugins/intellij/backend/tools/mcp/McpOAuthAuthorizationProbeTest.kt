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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
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

    @Test
    fun `requests advertised offline access alongside required challenge scopes`() {
        assertEquals(
            listOf("mcp", "offline_access"),
            oauthAuthorizationScopes(
                challengeScopes = listOf("mcp"),
                protectedResourceScopes = listOf("mcp", "offline_access"),
            ),
        )
    }

    @Test
    fun `does not request offline access when the protected resource does not advertise it`() {
        assertEquals(
            listOf("mcp"),
            oauthAuthorizationScopes(
                challengeScopes = listOf("mcp"),
                protectedResourceScopes = listOf("mcp"),
            ),
        )
    }

    @Test
    fun `coalesces concurrent refreshes for the same credential`() {
        val coordinator = McpOAuthRefreshCoordinator<String>()
        val refreshCount = AtomicInteger()
        val refreshStarted = CountDownLatch(1)
        val allowRefreshToFinish = CountDownLatch(1)
        val secondRefreshStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first =
                executor.submit<String> {
                    coordinator.refresh("devstack-mcp|https://mcp.example.test") {
                        refreshCount.incrementAndGet()
                        refreshStarted.countDown()
                        check(allowRefreshToFinish.await(5, TimeUnit.SECONDS))
                        "rotated-token"
                    }
                }
            check(refreshStarted.await(5, TimeUnit.SECONDS))
            val second =
                executor.submit<String> {
                    secondRefreshStarted.countDown()
                    coordinator.refresh("devstack-mcp|https://mcp.example.test") {
                        refreshCount.incrementAndGet()
                        "unexpected-second-refresh"
                    }
                }
            check(secondRefreshStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(1, refreshCount.get())

            allowRefreshToFinish.countDown()

            assertEquals("rotated-token", first.get(5, TimeUnit.SECONDS))
            assertEquals("rotated-token", second.get(5, TimeUnit.SECONDS))
            assertEquals(1, refreshCount.get())
        } finally {
            allowRefreshToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `shares a failed concurrent refresh without launching another request`() {
        val coordinator = McpOAuthRefreshCoordinator<String>()
        val refreshCount = AtomicInteger()
        val refreshStarted = CountDownLatch(1)
        val allowRefreshToFail = CountDownLatch(1)
        val secondRefreshStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first =
                executor.submit<String> {
                    coordinator.refresh("devstack-mcp|https://mcp.example.test") {
                        refreshCount.incrementAndGet()
                        refreshStarted.countDown()
                        check(allowRefreshToFail.await(5, TimeUnit.SECONDS))
                        error("token endpoint response was lost")
                    }
                }
            check(refreshStarted.await(5, TimeUnit.SECONDS))
            val second =
                executor.submit<String> {
                    secondRefreshStarted.countDown()
                    coordinator.refresh("devstack-mcp|https://mcp.example.test") {
                        refreshCount.incrementAndGet()
                        "unexpected-second-refresh"
                    }
                }
            check(secondRefreshStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(1, refreshCount.get())

            allowRefreshToFail.countDown()

            assertFails { first.get(5, TimeUnit.SECONDS) }
            assertFails { second.get(5, TimeUnit.SECONDS) }
            assertEquals(1, refreshCount.get())
        } finally {
            allowRefreshToFail.countDown()
            executor.shutdownNow()
        }
    }
}
