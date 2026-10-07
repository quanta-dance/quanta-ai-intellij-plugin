// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class BackendExecutionContextsServiceTest {
    @Test
    fun mcpDiscoveryChecksCanRunConcurrently() {
        val contexts = BackendExecutionContextsService()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)

        try {
            val jobs =
                List(2) {
                    contexts.mcpDiscoveryScope.launch {
                        started.countDown()
                        release.await(1, TimeUnit.SECONDS)
                    }
                }

            assertTrue(started.await(1, TimeUnit.SECONDS), "MCP discovery checks should start independently")
            release.countDown()
            runBlocking { jobs.joinAll() }
        } finally {
            release.countDown()
            contexts.dispose()
        }
    }
}
