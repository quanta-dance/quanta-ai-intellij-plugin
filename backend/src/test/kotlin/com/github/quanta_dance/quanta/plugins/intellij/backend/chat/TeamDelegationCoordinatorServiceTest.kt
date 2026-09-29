// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeamDelegationCoordinatorServiceTest {
    @Test
    fun `schedules one fan-in only after every registered task settles and registration is sealed`() {
        val tracker = TeamDelegationCoordinatorService.GroupTracker(expectedTaskCount = 3)
        tracker.register("developer", "Developer")
        tracker.register("reviewer", "Reviewer")
        tracker.register("tester", "Tester")

        assertNull(tracker.record("reviewer", "review found"))
        assertNull(tracker.record("developer", "implementation found"))
        assertNull(tracker.record("tester", "tests failed"))

        val reports = tracker.seal()

        assertEquals(
            listOf("Reviewer", "Developer", "Tester"),
            reports?.map(TeamDelegationCoordinatorService.TaskReport::participantName),
        )
        assertEquals(listOf("review found", "implementation found", "tests failed"), reports?.map { it.text })
    }

    @Test
    fun `ignores duplicate and unknown completions after fan-in is scheduled`() {
        val tracker = TeamDelegationCoordinatorService.GroupTracker(expectedTaskCount = 2)
        tracker.register("developer", "Developer")
        tracker.register("tester", "Tester")
        tracker.seal()

        assertNull(tracker.record("unknown", "ignored"))
        assertNull(tracker.record("developer", "done"))
        assertNull(tracker.record("developer", "duplicate"))

        val reports = tracker.record("tester", "failed")

        assertEquals(listOf("done", "failed"), reports?.map { it.text })
        assertNull(tracker.record("tester", "late duplicate"))
    }

    @Test
    fun `does not complete a group before every requested participant is registered`() {
        val tracker = TeamDelegationCoordinatorService.GroupTracker(expectedTaskCount = 2)
        tracker.register("developer", "Developer")
        tracker.record("developer", "done")

        assertNull(tracker.seal())
    }
}
