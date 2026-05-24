// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.project.SearchInFiles
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiManager
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchInFilesTest {
    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        mockkStatic(ProjectFileIndex::class)
        mockkStatic(ApplicationManager::class)
        mockkStatic(PsiManager::class)

        val app = mockk<Application>()
        every { ApplicationManager.getApplication() } returns app
        every { app.runReadAction<String?>(any()) } answers { firstArg<() -> String?>().invoke() }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `invalid regex returns friendly message`() {
        val tool = SearchInFiles()
        tool.query = "(unclosed"
        val project = mockk<Project>()
        val res = tool.execute(project)
        assertTrue(res.modelSummary?.contains("Invalid regular expression") == true)
        assertEquals(0, res.matches.size)
    }

    @Test
    fun `iterateContent exception returns error summary`() {
        val tool = SearchInFiles()
        tool.query = "foo"
        val project = mockk<Project>()
        val fileIndex = mockk<ProjectFileIndex>()

        every { ProjectFileIndex.getInstance(project) } returns fileIndex
        every { fileIndex.iterateContent(any()) } throws RuntimeException("boom")

        val res = tool.execute(project)
        assertTrue(res.modelSummary?.contains("Search failed: boom") == true)
        assertEquals(0, res.matches.size)
    }

}
