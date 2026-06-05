// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.logging

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.io.PrintStream

/**
 * Small logging helper used throughout the backend.
 *
 * It centralizes a few logging conventions:
 * - prefer debug output when debug logging is enabled
 * - fall back to info-level visibility in internal/test mode
 * - echo selected messages to stderr/console when helpful during development
 */
object QDLog {
    /**
     * Returns true when the IDE is running in a developer-oriented mode
     * where extra console logging is useful.
     */
    private fun isDevMode(): Boolean =
        try {
            val app = ApplicationManager.getApplication()
            app != null && (app.isInternal || app.isUnitTestMode)
        } catch (_: Throwable) {
            false
        }

    /**
     * Logs a message at debug level when debug logging is enabled.
     * In internal or unit-test mode, falls back to info so the message is still visible.
     */
    fun debug(
        logger: Logger,
        msg: () -> String,
    ) {
        if (logger.isDebugEnabled) {
            logger.debug(msg())
        } else if (isDevMode()) {
            logger.info(msg())
        }
    }

    /**
     * Logs a message at info level and echoes it to the console in developer mode.
     */
    fun info(
        logger: Logger,
        msg: () -> String,
    ) {
        val text = msg()
        logger.info(text)
        // Keep console echo for developer mode, but avoid duplicating high-volume operational logs.
        if (isDevMode()) {
            println(text)
        }
    }

    /**
     * Logs a warning message.
     */
    fun warn(
        logger: Logger,
        msg: () -> String,
    ) {
        logger.warn(msg())
    }

    /**
     * Logs a warning message and optionally prints the exception stack trace to stderr.
     */
    fun warn(
        logger: Logger,
        msg: () -> String,
        t: Throwable? = null,
    ) {
        val text = msg()
        if (t != null) {
            logger.warn(text, t)
        } else {
            logger.warn(text)
        }
        t?.printStackTrace(PrintStream(System.err))
    }

    /**
     * Logs an error message and optionally prints the exception stack trace to stderr.
     */
    fun error(
        logger: Logger,
        msg: () -> String,
        t: Throwable? = null,
    ) {
        val text = msg()
        if (t != null) {
            logger.error(text, t)
        } else {
            logger.error(text)
        }
        if (isDevMode()) {
            t?.printStackTrace(PrintStream(System.err))
        }
    }
}
