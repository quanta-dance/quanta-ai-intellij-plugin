// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeamDelegationCoordinatorServiceTest {
    @Test
    fun `schedules one fan-in only after every registered task settles`() {
        val tracker = TeamDelegationCoordinatorService.GroupTracker(listOf("developer", "reviewer", "tester"))

        assertNull(tracker.record("reviewer", TeamDelegationCoordinatorService.TaskReport("Reviewer", "review found")))
        assertNull(
            tracker.record(
                "developer",
                TeamDelegationCoordinatorService.TaskReport("Developer", "implementation found"),
            ),
        )

        val reports =
            tracker.record(
                "tester",
                TeamDelegationCoordinatorService.TaskReport("Tester", "tests failed"),
            )

        assertEquals(
            listOf("Reviewer", "Developer", "Tester"),
            reports?.map(TeamDelegationCoordinatorService.TaskReport::agentRole),
        )
        assertEquals(listOf("review found", "implementation found", "tests failed"), reports?.map { it.text })
    }

    @Test
    fun `ignores duplicate and unknown completions after fan-in is scheduled`() {
        val tracker = TeamDelegationCoordinatorService.GroupTracker(listOf("developer", "tester"))

        assertNull(tracker.record("unknown", TeamDelegationCoordinatorService.TaskReport("Unknown", "ignored")))
        assertNull(tracker.record("developer", TeamDelegationCoordinatorService.TaskReport("Developer", "done")))
        assertNull(tracker.record("developer", TeamDelegationCoordinatorService.TaskReport("Developer", "duplicate")))

        val reports = tracker.record("tester", TeamDelegationCoordinatorService.TaskReport("Tester", "failed"))

        assertEquals(listOf("done", "failed"), reports?.map { it.text })
        assertNull(tracker.record("tester", TeamDelegationCoordinatorService.TaskReport("Tester", "late duplicate")))
    }
}
