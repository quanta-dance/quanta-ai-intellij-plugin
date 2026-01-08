// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services.ui

import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

class DelayedSpinner(private val svc: ToolWindowService) {
    private val shown = AtomicBoolean(false)
    private var handle: ToolWindowService.SpinnerHandle? = null
    private var timer: Timer? = null

    fun startWithDelay(
        title: String,
        delayMs: Long = 300,
    ) {
        timer =
            Timer("delayed-spinner", true).apply {
                schedule(
                    object : TimerTask() {
                        override fun run() {
                            if (shown.compareAndSet(false, true)) handle = svc.startSpinner(title)
                        }
                    },
                    delayMs,
                )
            }
    }

    fun stopSuccess() {
        timer?.cancel()
        handle?.stopSuccess()
    }

    fun stopError(msg: String) {
        timer?.cancel()
        handle?.stopError(msg)
    }
}
