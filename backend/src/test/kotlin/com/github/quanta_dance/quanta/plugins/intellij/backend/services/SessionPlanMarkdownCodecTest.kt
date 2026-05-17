// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionPlanMarkdownCodecTest {
    @Test
    fun `render and parse roundtrip preserves plan state`() {
        val plan =
            SessionPlan(
                status = "ACTIVE",
                goal = "Refactor plan mode",
                definitionOfDone = "Typed state is canonical",
                tasks =
                    listOf(
                        SessionPlanTask("Extract coordinator", completed = true),
                        SessionPlanTask("Add tests", completed = false),
                    ),
            )

        val rendered = SessionPlanMarkdownCodec.render(plan)
        val parsed = SessionPlanMarkdownCodec.parse(rendered)

        assertEquals("ACTIVE", parsed.normalizedStatus())
        assertEquals(plan.goal, parsed.goal)
        assertEquals(plan.definitionOfDone, parsed.definitionOfDone)
        assertEquals(plan.tasks, parsed.tasks)
    }

    @Test
    fun `empty plan renders empty text`() {
        assertEquals("", SessionPlanMarkdownCodec.render(SessionPlan()))
    }

    @Test
    fun `unchecked helpers reflect task completion`() {
        val plan =
            SessionPlan(
                tasks =
                    listOf(
                        SessionPlanTask("Done", completed = true),
                        SessionPlanTask("Pending", completed = false),
                    ),
            )

        assertTrue(plan.hasUncheckedTasks())
        assertEquals(listOf("Pending"), plan.uncheckedTaskTexts())
        assertEquals(listOf("Done"), plan.completedTaskTexts())
        assertFalse(plan.completedTaskTexts().contains("Pending"))
    }
}
