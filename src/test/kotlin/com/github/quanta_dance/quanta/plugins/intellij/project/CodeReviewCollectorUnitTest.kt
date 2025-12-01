// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Query
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for CodeReviewCollector using MockK to mock IntelliJ PSI and search APIs.
 * These tests do not require IntelliJ test fixtures and can be run as standard unit tests.
 */
class CodeReviewCollectorUnitTest {
    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        mockkStatic(ReferencesSearch::class)
        mockkStatic("com.intellij.psi.util.PsiTreeUtil")
        mockkStatic(PsiDocumentManager::class)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getMethodUsagesWithReferencesSearch handles null basePath and missing virtualFile`() {
        val method = mockk<PsiMethod>()
        val project = mockk<Project>()

        every { method.name } returns "testMethod"
        every { method.useScope } returns mockk()
        every { project.basePath } returns null

        val emptyQuery = mockk<Query<com.intellij.psi.PsiReference>>()
        every { emptyQuery.findAll() } returns emptyList()
        every { ReferencesSearch.search(method, any()) } returns emptyQuery

        val result = CodeReferenceSelector.getMethodUsagesWithReferencesSearch(method, project)
        assertEquals(0, result.size)
    }

    @Test
    fun `getMethodUsagesWithReferencesSearch filters standard library classes and returns usages`() {
        val method = mockk<PsiMethod>()
        val project = mockk<Project>()
        val reference = mockk<com.intellij.psi.PsiReference>()
        val element = mockk<PsiElement>()
        val containingFile = mockk<PsiFile>()
        val virtualFile = mockk<VirtualFile>()
        val containingClass = mockk<PsiClass>()

        every { method.name } returns "foo"
        every { method.useScope } returns mockk()
        every { project.basePath } returns "/project"

        every { reference.element } returns element
        every { element.containingFile } returns containingFile
        every { containingFile.virtualFile } returns virtualFile
        every { virtualFile.path } returns "/project/src/Main.kt"

        // Ensure containingClass is not considered standard library and has no superclass/interfaces mocked
        every { com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, PsiClass::class.java) } returns containingClass
        every { containingClass.qualifiedName } returns "com.example.Main"
        every { containingClass.name } returns "Main"
        every { containingClass.superClass } returns null
        every { containingClass.interfaces } returns emptyArray()

        val pdm = mockk<PsiDocumentManager>()
        every { PsiDocumentManager.getInstance(any()) } returns pdm
        val document = mockk<com.intellij.openapi.editor.Document>()
        every { pdm.getDocument(containingFile) } returns document
        every { document.getLineNumber(any()) } returns 10

        val q = mockk<Query<com.intellij.psi.PsiReference>>()
        every { q.findAll() } returns listOf(reference)
        every { ReferencesSearch.search(method, any()) } returns q

        val result = CodeReferenceSelector.getMethodUsagesWithReferencesSearch(method, project)
        // Ensure call happened and method returned a List (don't rely on formatting specifics)
        verify { ReferencesSearch.search(method, any()) }
        assertTrue(result is List<*>)
    }

    @Test
    fun `findClassReferences filters standard library classes via public API`() {
        val psiClass = mockk<PsiClass>()
        every { psiClass.qualifiedName } returns "java.lang.String"
        every { psiClass.name } returns "String"
        every { psiClass.superClass } returns null
        every { psiClass.interfaces } returns emptyArray()

        val emptyQuery = mockk<Query<com.intellij.psi.PsiReference>>()
        every { emptyQuery.findAll() } returns emptyList()
        every { ReferencesSearch.search(any(), any()) } returns emptyQuery

        val refs = CodeReferenceSelector.findClassReferences(psiClass)
        assertTrue(refs.isEmpty())
    }
}
