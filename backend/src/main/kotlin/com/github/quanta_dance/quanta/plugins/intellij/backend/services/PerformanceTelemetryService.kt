// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.UUID

/**
 * Records privacy-safe local timing summaries for agent execution.
 *
 * This service deliberately records only operation names, elapsed time, execution class, and bounded
 * counts. It never receives prompts, responses, file paths, tool arguments, tokens, or source text.
 * The small API is transport-neutral so an opt-in OpenTelemetry implementation can later mirror these
 * measurements without changing turn orchestration.
 */
@Service(Service.Level.PROJECT)
class PerformanceTelemetryService : Disposable {
    private val logger = Logger.getInstance(PerformanceTelemetryService::class.java)
    private val currentTurn = ThreadLocal<TurnMeasurement?>()

    /** Runs [block] as one measured agent turn and emits a summary when it completes. */
    fun <T> measureTurn(
        executionClass: String,
        queueWaitMs: Long,
        block: () -> T,
    ): T {
        val previousTurn = currentTurn.get()
        val turn =
            TurnMeasurement(
                id = UUID.randomUUID().toString().take(8),
                executionClass = executionClass,
                queueWaitMs = queueWaitMs,
            )
        currentTurn.set(turn)
        return try {
            block()
        } finally {
            currentTurn.set(previousTurn)
            emitTurnSummary(turn)
        }
    }

    /** Records a timed sub-phase for the current turn, if one is active. */
    fun recordCurrentPhase(
        phase: String,
        durationNanos: Long,
        count: Int? = null,
    ) {
        val durationMs = durationNanos / NANOS_PER_MILLISECOND
        val turn = currentTurn.get()
        if (turn != null) {
            turn.record(phase, durationMs, count)
        } else {
            QDLog.debug(logger) {
                "quanta.perf phase.completed phase=$phase durationMs=$durationMs${
                    count?.let { " count=$it" }.orEmpty()
                }"
            }
        }
        emitSlowPhase(phase, durationMs, count)
    }

    /** Measures a local sub-phase while keeping the calling code concise. */
    fun <T> measureCurrentPhase(
        phase: String,
        count: Int? = null,
        block: () -> T,
    ): T {
        val startedAtNanos = System.nanoTime()
        return try {
            block()
        } finally {
            recordCurrentPhase(phase, System.nanoTime() - startedAtNanos, count)
        }
    }

    override fun dispose() {
        currentTurn.remove()
    }

    private fun emitTurnSummary(turn: TurnMeasurement) {
        val totalMs = (System.nanoTime() - turn.startedAtNanos) / NANOS_PER_MILLISECOND
        val phases =
            turn.phases.entries.joinToString(separator = " ") { (name, measurement) ->
                "$name=${measurement.durationMs}ms"
            }
        val summary =
            "quanta.perf turn.completed id=${turn.id} class=${turn.executionClass} " +
                "queueWaitMs=${turn.queueWaitMs} totalMs=$totalMs${if (phases.isBlank()) "" else " $phases"}"
        if (totalMs >= SLOW_TURN_THRESHOLD_MS) {
            QDLog.info(logger) { summary }
        } else {
            QDLog.debug(logger) { summary }
        }
    }

    private fun emitSlowPhase(
        phase: String,
        durationMs: Long,
        count: Int?,
    ) {
        if (durationMs < SLOW_PHASE_THRESHOLD_MS) return
        QDLog.info(logger) {
            "quanta.perf phase.slow phase=$phase durationMs=$durationMs${count?.let { " count=$it" }.orEmpty()}"
        }
    }

    private class TurnMeasurement(
        val id: String,
        val executionClass: String,
        val queueWaitMs: Long,
        val startedAtNanos: Long = System.nanoTime(),
    ) {
        val phases: MutableMap<String, PhaseMeasurement> = linkedMapOf()

        fun record(
            phase: String,
            durationMs: Long,
            count: Int?,
        ) {
            val existing = phases[phase]
            phases[phase] =
                PhaseMeasurement(
                    durationMs = (existing?.durationMs ?: 0L) + durationMs,
                    count = (existing?.count ?: 0) + (count ?: 1),
                )
        }
    }

    private data class PhaseMeasurement(
        val durationMs: Long,
        val count: Int,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val SLOW_PHASE_THRESHOLD_MS = 250L
        const val SLOW_TURN_THRESHOLD_MS = 1_000L
    }
}
