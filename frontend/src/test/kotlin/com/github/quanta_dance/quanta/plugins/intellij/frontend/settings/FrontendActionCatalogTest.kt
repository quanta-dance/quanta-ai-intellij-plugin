// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class FrontendActionCatalogTest {
    @Test
    fun `actions round trip through Kotlin serialization`() {
        val actions =
            listOf(
                FrontendActionCatalog.ActionConfig(
                    id = "custom",
                    label = "Custom action",
                    instruction = "Use a JSON-safe instruction: \"quoted\".",
                ),
            )

        assertEquals(actions, FrontendActionCatalog.decode(FrontendActionCatalog.encode(actions)))
    }
}
