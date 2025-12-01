// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.mcp

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import kotlin.test.Test
import kotlin.test.assertNotNull

class McpToolTest {
    @Test
    fun constructsClient() {
        val client = Client(Implementation(name = "mcp-client-cli", version = "1.0.0"), ClientOptions())
        assertNotNull(client)
    }
}
