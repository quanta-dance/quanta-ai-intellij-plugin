// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.intellij.openapi.diagnostic.Logger
import com.openai.models.responses.ResponseUsage
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks cumulative token usage for OpenAI interactions.
 *
 * This keeps counter management and usage-event publishing out of [OpenAIService] so the service can
 * focus on orchestration while preserving the existing global-usage semantics.
 */
class OpenAIUsageTracker(
    private val logger: Logger,
    private val publishSnapshot: (OpenAIService.UsageSnapshot) -> Unit,
) {
    private val inputTokens: AtomicLong = AtomicLong(0)
    private val outputTokens: AtomicLong = AtomicLong(0)
    private val totalTokens: AtomicLong = AtomicLong(0)

    fun snapshot(): OpenAIService.UsageSnapshot =
        OpenAIService.UsageSnapshot(
            inputTokens = inputTokens.get(),
            outputTokens = outputTokens.get(),
            totalTokens = totalTokens.get(),
        )

    fun recordUsage(
        tag: String,
        usage: ResponseUsage,
        reportToUi: Boolean,
    ) {
        val inTok =
            try {
                usage.inputTokens()
            } catch (_: Throwable) {
                0L
            }
        val outTok =
            try {
                usage.outputTokens()
            } catch (_: Throwable) {
                0L
            }
        val totalTok =
            try {
                usage.totalTokens()
            } catch (_: Throwable) {
                inTok + outTok
            }

        val snapshot =
            OpenAIService.UsageSnapshot(
                inputTokens = inputTokens.addAndGet(inTok),
                outputTokens = outputTokens.addAndGet(outTok),
                totalTokens = totalTokens.addAndGet(totalTok),
            )

        if (reportToUi) {
            try {
                publishSnapshot(snapshot)
            } catch (_: Throwable) {
            }
        }

        try {
            logger.info(
                "Usage: tag=$tag in=$inTok out=$outTok total=$totalTok global(in=${snapshot.inputTokens} out=${snapshot.outputTokens} total=${snapshot.totalTokens})",
            )
        } catch (_: Throwable) {
        }
    }

    fun reset(reportToUi: Boolean = true) {
        inputTokens.set(0)
        outputTokens.set(0)
        totalTokens.set(0)
        if (reportToUi) {
            try {
                publishSnapshot(OpenAIService.UsageSnapshot(0, 0, 0))
            } catch (_: Throwable) {
            }
        }
    }
}
