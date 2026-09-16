// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpRemoteConnectionPolicyTest {
    @Test
    fun defersUrlServerDiscoveryBecauseItCanRequireInteractiveAuthorization() {
        assertTrue(requiresInteractiveMcpConnection(McpServerConfig(url = "https://example.test/mcp")))
    }

    @Test
    fun doesNotDeferLocalStdioServerDiscovery() {
        assertFalse(requiresInteractiveMcpConnection(McpServerConfig(command = "local-mcp-server")))
    }
}
