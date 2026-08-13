// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.QuantaPluginBackendBundle"

object QuantaPluginBackendBundle : DynamicBundle(BUNDLE) {
    @JvmStatic
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ) = getMessage(key, *params)

    @Suppress("unused")
    @JvmStatic
    fun messagePointer(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ) = getLazyMessage(key, *params)
}
