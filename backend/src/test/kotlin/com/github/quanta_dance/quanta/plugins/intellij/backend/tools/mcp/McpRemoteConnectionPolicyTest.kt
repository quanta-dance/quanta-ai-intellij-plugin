// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp

import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpRemoteConnectionPolicyTest {
    @Test
    fun defersUrlServerDiscoveryBecauseItCanRequireInteractiveAuthorization() {
        assertTrue(requiresInteractiveMcpConnection(McpServerConfig(url = "https://example.test/mcp")))
    }

    @Test
    fun permitsHttpOnlyForLiteralLoopbackMcpEndpoints() {
        assertTrue(isLoopbackMcpUrl("http://localhost:3000/mcp"))
        assertTrue(isLoopbackMcpUrl("http://127.0.0.1:3000/mcp"))
        assertTrue(isLoopbackMcpUrl("http://[::1]:3000/mcp"))
        assertFalse(isLoopbackMcpUrl("http://example.test/mcp"))
        assertFalse(isLoopbackMcpUrl("https://localhost:3000/mcp"))
    }

    @Test
    fun doesNotDeferLocalStdioServerDiscovery() {
        assertFalse(requiresInteractiveMcpConnection(McpServerConfig(command = "local-mcp-server")))
    }

    @Test
    fun recognizesExpectedStdioShutdownPipeErrorsOnly() {
        assertTrue(isExpectedStdioTransportShutdownError(RuntimeException(IOException("Stream closed"))))
        assertTrue(isExpectedStdioTransportShutdownError(RuntimeException(IOException("Broken pipe"))))
        assertFalse(isExpectedStdioTransportShutdownError(IOException("Connection reset")))
        assertFalse(isExpectedStdioTransportShutdownError(IllegalStateException("Stream closed")))
    }

    @Test
    fun schedulesRefreshBeforeTokenExpiryAndNeverUsesNegativeDelay() {
        assertEquals(240_000, oauthRefreshDelayMillis(expiresAtSeconds = 1_000, nowSeconds = 700, leewaySeconds = 60))
        assertEquals(0, oauthRefreshDelayMillis(expiresAtSeconds = 750, nowSeconds = 700, leewaySeconds = 60))
    }

    @Test
    fun `preserves stored credentials through bounded preflight recovery attempts`() {
        assertTrue(hasOAuthRefreshRecoveryAttemptRemaining(attempt = 0, maxAttempts = 3))
        assertTrue(hasOAuthRefreshRecoveryAttemptRemaining(attempt = 2, maxAttempts = 3))
        assertFalse(hasOAuthRefreshRecoveryAttemptRemaining(attempt = 3, maxAttempts = 3))
    }

    @Test
    fun summarizesInitializationTimeoutAsRetryableGuidance() {
        val error = RuntimeException(TimeoutException("MCP initialize timed out"))

        assertEquals(
            "MCP initialization did not finish within 30 seconds. Check the server configuration, then select Retry.",
            mcpConnectionErrorMessage(error),
        )
    }
}
