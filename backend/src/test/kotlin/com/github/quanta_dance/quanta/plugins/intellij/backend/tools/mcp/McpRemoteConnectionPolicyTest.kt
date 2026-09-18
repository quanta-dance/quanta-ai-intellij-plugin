// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp

import java.io.IOException
import kotlin.test.Test
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
    fun recognizesOnlyTheExpectedStdioShutdownPipeError() {
        assertTrue(isExpectedStdioTransportShutdownError(RuntimeException(IOException("Stream closed"))))
        assertFalse(isExpectedStdioTransportShutdownError(IOException("Connection reset")))
        assertFalse(isExpectedStdioTransportShutdownError(IllegalStateException("Stream closed")))
    }
}
