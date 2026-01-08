// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.github.quanta_dance.quanta.plugins.intellij.tools.project.SearchInFiles
import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
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
        mockkStatic(FindInProjectUtil::class)
        mockkStatic(GlobalSearchScope::class)
        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>()
        every { ApplicationManager.getApplication() } returns app
        every { app.runReadAction(any<Runnable>()) } answers {
            arg<Runnable>(0).run()
            null
        }
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
    fun `findUsages exception returns error summary`() {
        val tool = SearchInFiles()
        tool.query = "foo"
        val project = mockk<Project>()
        every { project.basePath } returns "/project"
        // Stub projectScope to avoid getUserData calls in GlobalSearchScope.projectScope
        every { GlobalSearchScope.projectScope(project) } returns
            object : GlobalSearchScope(project) {
                override fun contains(file: VirtualFile) = true

                override fun isSearchInModuleContent(aModule: com.intellij.openapi.module.Module) = true

                override fun isSearchInLibraries() = false
            }
        every {
            FindInProjectUtil.findUsages(
                any<FindModel>(),
                project,
                any<Processor<UsageInfo>>(),
                any(),
            )
        } throws RuntimeException("boom")
        val res = tool.execute(project)
        assertTrue(res.modelSummary?.contains("Search failed") == true)
        assertEquals(0, res.matches.size)
    }

    @Test
    fun `handles offsets out of range gracefully`() {
        val tool = SearchInFiles()
        tool.query = "foo"
        val project = mockk<Project>()
        every { project.basePath } returns "/project"
        // Stub projectScope
        every { GlobalSearchScope.projectScope(project) } returns
            object : GlobalSearchScope(project) {
                override fun contains(file: VirtualFile) = true

                override fun isSearchInModuleContent(aModule: com.intellij.openapi.module.Module) = true

                override fun isSearchInLibraries() = false
            }

        val usage = mockk<UsageInfo>()
        val vfile = mockk<VirtualFile>()

        every { usage.virtualFile } returns vfile
        every { usage.navigationOffset } returns Int.MAX_VALUE
        every { vfile.path } returns "/tmp/test.kt"
        every { vfile.length } returns 10L

        every { FindInProjectUtil.findUsages(any<FindModel>(), project, any<Processor<UsageInfo>>(), any()) } answers {
            val proc = arg<Processor<UsageInfo>>(2)
            proc.process(usage)
        }

        // Mock psi and document lookups to return a short text
        mockkStatic(PsiManager::class)
        val psiManager = mockk<PsiManager>()
        every { PsiManager.getInstance(project) } returns psiManager
        val psiFile = mockk<PsiFile>()
        every { psiManager.findFile(vfile) } returns psiFile
        every { psiFile.text } returns "line1\nline2"

        val docManager = mockk<PsiDocumentManager>()
        mockkStatic(PsiDocumentManager::class)
        every { PsiDocumentManager.getInstance(project) } returns docManager
        every { docManager.getDocument(any()) } returns null

        val res = tool.execute(project)
        // Should not throw and should return empty or safe results
        assertTrue(res.matches.isEmpty() || res.matches.all { it.snippet.length <= 240 })
    }

    @Test
    fun `successful search returns matches and summary`() {
        val tool = SearchInFiles()
        tool.query = "hello"
        tool.maxResults = 5
        val project = mockk<Project>()
        every { project.basePath } returns "/project"

        // Stub projectScope
        every { GlobalSearchScope.projectScope(project) } returns
            object : GlobalSearchScope(project) {
                override fun contains(file: VirtualFile) = true

                override fun isSearchInModuleContent(aModule: com.intellij.openapi.module.Module) = true

                override fun isSearchInLibraries() = false
            }

        val usage = mockk<UsageInfo>()
        val vfile = mockk<VirtualFile>()

        every { usage.virtualFile } returns vfile
        every { usage.navigationOffset } returns 0
        every { vfile.path } returns "/project/src/Main.kt"
        every { vfile.length } returns 100L

        every { FindInProjectUtil.findUsages(any<FindModel>(), project, any<Processor<UsageInfo>>(), any()) } answers {
            val proc = arg<Processor<UsageInfo>>(2)
            proc.process(usage)
        }

        val psiManager = mockk<PsiManager>()
        mockkStatic(PsiManager::class)
        every { PsiManager.getInstance(project) } returns psiManager
        val psiFile = mockk<PsiFile>()
        every { psiManager.findFile(vfile) } returns psiFile
        every { psiFile.text } returns "hello world\nsecond line"

        val docManager = mockk<PsiDocumentManager>()
        mockkStatic(PsiDocumentManager::class)
        every { PsiDocumentManager.getInstance(project) } returns docManager
        every { docManager.getDocument(psiFile) } returns null

        val res = tool.execute(project)
        assertTrue(res.matches.isNotEmpty())
        assertTrue(res.modelSummary!!.contains("Project context for query"))
    }
}
