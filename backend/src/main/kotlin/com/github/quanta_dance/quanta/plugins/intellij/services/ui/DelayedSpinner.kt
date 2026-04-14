package com.github.quanta_dance.quanta.plugins.intellij.services.ui

import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

class DelayedSpinner(private val svc: ToolWindowService) {
    private val shown = AtomicBoolean(false)
    private var handle: Any? = null
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
    }

    fun stopError(@Suppress("unused") msg: String) {
        timer?.cancel()
    }
}
