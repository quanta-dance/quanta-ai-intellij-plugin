// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.CreateOrUpdateFile
import com.intellij.openapi.project.Project
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals

class CreateOrUpdateFileTest {
    @Test
    fun `execute returns message when project base path is missing`() {
        val tool = CreateOrUpdateFile()
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn(null)

        tool.filePath = "some/file.txt"
        tool.content = "content"
        tool.validateAfterUpdate = false

        val result = tool.execute(project)
        assertEquals("Project base path not found.", result)
    }
}
