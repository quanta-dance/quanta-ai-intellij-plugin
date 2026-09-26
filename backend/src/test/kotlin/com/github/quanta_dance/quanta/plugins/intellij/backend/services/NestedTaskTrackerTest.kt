// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NestedTaskTrackerTest {
    @Test
    fun `waits for all child tasks and preserves successful and failed reports`() {
        val tracker = NestedTaskTracker()
        tracker.register("tester")
        tracker.register("reviewer")

        assertTrue(tracker.record("tester", "Tester", "34 tests"))
        assertFalse(tracker.isComplete)
        assertTrue(tracker.record("reviewer", "Reviewer", "Failed: unavailable"))
        assertTrue(tracker.isComplete)
        assertEquals(
            listOf("Tester: 34 tests", "Reviewer: Failed: unavailable"),
            tracker.reports().map { "${it.role}: ${it.text}" },
        )
    }

    @Test
    fun `ignores duplicate and unknown child completions`() {
        val tracker = NestedTaskTracker()
        tracker.register("tester")

        assertFalse(tracker.record("unknown", "Unknown", "ignored"))
        assertTrue(tracker.record("tester", "Tester", "34 tests"))
        assertFalse(tracker.record("tester", "Tester", "duplicate"))
        assertEquals(listOf("34 tests"), tracker.reports().map(NestedTaskReport::text))
    }
}
