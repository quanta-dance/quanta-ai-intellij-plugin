// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger

object QDLog {
    private fun isDevMode(): Boolean =
        try {
            val app = ApplicationManager.getApplication()
            app != null && (app.isInternal || app.isUnitTestMode)
        } catch (_: Throwable) {
            false
        }

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

    fun info(
        logger: Logger,
        msg: () -> String,
    ) {
        val text = msg()
        logger.info(text)
        if (isDevMode()) {
            // Echo INFO to console during runIde/internal mode for easier dev debugging
            kotlin.io.println(text)
        }
    }

    fun warn(
        logger: Logger,
        msg: () -> String,
    ) {
        logger.warn(msg())
    }

    fun warn(
        logger: Logger,
        msg: () -> String,
        t: Throwable? = null,
    ) {
        if (t != null) logger.warn(msg(), t) else logger.warn(msg())
    }

    fun error(
        logger: Logger,
        msg: () -> String,
        t: Throwable? = null,
    ) {
        if (t != null) logger.error(msg(), t) else logger.error(msg())
    }
}
