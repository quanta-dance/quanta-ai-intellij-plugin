// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.mcp

import io.modelcontextprotocol.spec.McpSchema.Implementation
import kotlin.test.Test
import kotlin.test.assertEquals

class McpToolTest {
    @Test
    fun constructsClientImplementation() {
        val implementation = Implementation("mcp-client-cli", "1.0.0")

        assertEquals("mcp-client-cli", implementation.name())
        assertEquals("1.0.0", implementation.version())
    }
}
