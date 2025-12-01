// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.github.quanta_dance.quanta.plugins.intellij.project.VersionUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import kotlin.test.assertTrue

class PatchFilePlatformTest : BasePlatformTestCase() {
    private fun currentVersion(file: PsiFile): Long {
        val project = project
        val vf = file.virtualFile
        val psiStamp = file.modificationStamp
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)
        val docStamp = doc?.modificationStamp ?: 0L
        val vfsStamp =
            try {
                vf.modificationStamp
            } catch (_: Throwable) {
                0L
            }
        return VersionUtil.computeVersion(psiStamp, docStamp, vfsStamp)
    }

    private fun createUnderProject(
        relativePath: String,
        content: String,
    ): PsiFile {
        val base = project.basePath ?: error("Project basePath is null in platform test")
        val io = File(base, relativePath)
        io.parentFile.mkdirs()
        io.writeText(content)
        val vFile =
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)
                ?: error("Failed to create VFS file for $relativePath")
        myFixture.configureFromExistingVirtualFile(vFile)
        return PsiManager.getInstance(project).findFile(vFile)!!
    }

    fun testApplySinglePatch() {
        val initial =
            """
            |line1
            |line2
            |line3
            """.trimMargin()
        val psi = createUnderProject("src/A.kt", initial)
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        FileDocumentManager.getInstance().saveDocument(doc)

        val tool =
            PatchFile().apply {
                filePath = "src/A.kt"
                patches =
                    listOf(
                        PatchFile.Patch(fromLine = 2, toLine = 2, newContent = "LINE2", expectedText = "line2"),
                    )
                validateAfterUpdate = false
            }
        val res = tool.execute(project)
        assertTrue(res.contains("Patched 1 range(s)"), "Result should indicate one patch applied: $res")
        val lines = doc.text.split("\n")
        assertTrue(lines.size >= 3 && lines[1] == "LINE2", "Second line should be replaced. Text: ${doc.text}")
    }

    fun testExpectedTextMismatchStopsAll() {
        val initial =
            """
            |a
            |b
            |c
            """.trimMargin()
        val psi = createUnderProject("src/B.kt", initial)
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        FileDocumentManager.getInstance().saveDocument(doc)

        val tool =
            PatchFile().apply {
                filePath = "src/B.kt"
                patches =
                    listOf(
                        PatchFile.Patch(fromLine = 2, toLine = 2, newContent = "B", expectedText = "MISMATCH"),
                        PatchFile.Patch(fromLine = 3, toLine = 3, newContent = "C"),
                    )
                stopOnMismatch = true
                validateAfterUpdate = false
            }
        val res = tool.execute(project)
        assertTrue(res.contains("mismatch") && res.contains("Aborted"), "Should report mismatch and abort: $res")
        assertEquals(initial, doc.text)
    }

    fun testVersionMismatchAborts() {
        val initial =
            """
            |x
            |y
            |z
            """.trimMargin()
        val psi = createUnderProject("src/C.kt", initial)
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        FileDocumentManager.getInstance().saveDocument(doc)
        val version = currentVersion(psi)

        val tool =
            PatchFile().apply {
                filePath = "src/C.kt"
                patches =
                    listOf(
                        PatchFile.Patch(fromLine = 1, toLine = 1, newContent = "X", expectedText = "x"),
                    )
                expectedFileVersion = version + 1 // force mismatch
                validateAfterUpdate = false
            }
        val res = tool.execute(project)
        assertTrue(res.contains("Version mismatch"), "Should detect version mismatch: $res")
        assertEquals(initial, doc.text)
    }

    fun testSkipMismatchesAppliesOthers() {
        val initial =
            """
            |one
            |two
            |three
            |four
            """.trimMargin()
        val psi = createUnderProject("src/D.kt", initial)
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        FileDocumentManager.getInstance().saveDocument(doc)

        val tool =
            PatchFile().apply {
                filePath = "src/D.kt"
                patches =
                    listOf(
                        // mismatch
                        PatchFile.Patch(fromLine = 2, toLine = 2, newContent = "TWO", expectedText = "MIS"),
                        // ok
                        PatchFile.Patch(fromLine = 4, toLine = 4, newContent = "FOUR", expectedText = "four"),
                    )
                stopOnMismatch = false
                validateAfterUpdate = false
            }
        val res = tool.execute(project)
        assertTrue(res.contains("Patched 1 range(s)"), "Should apply only the second patch: $res")
        val lines = doc.text.split("\n")
        assertEquals("four".uppercase(), lines[3])
        assertEquals("two", lines[1]) // unchanged due to mismatch
    }

    fun testMultipleRangesBottomToTopNoShift() {
        val initial =
            """
            |a
            |b
            |c
            |d
            """.trimMargin()
        val psi = createUnderProject("src/E.kt", initial)
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        FileDocumentManager.getInstance().saveDocument(doc)

        val tool =
            PatchFile().apply {
                filePath = "src/E.kt"
                patches =
                    listOf(
                        PatchFile.Patch(fromLine = 3, toLine = 4, newContent = "CC\nDD", expectedText = "c\nd"),
                        PatchFile.Patch(fromLine = 1, toLine = 1, newContent = "AA", expectedText = "a"),
                    )
                stopOnMismatch = true
                validateAfterUpdate = false
            }
        val res = tool.execute(project)
        assertTrue(res.contains("Patched 2 range(s)"), "Both patches should apply: $res")
        val lines = doc.text.split("\n")
        assertEquals(listOf("AA", "b", "CC", "DD"), lines.take(4))
    }

    fun testStartLineBeyondDocumentSkippedWhenAllowed() {
        val initial =
            """
            |x
            |y
            """.trimMargin()
        val psi = createUnderProject("src/F.kt", initial)
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)!!
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        FileDocumentManager.getInstance().saveDocument(doc)

        val tool =
            PatchFile().apply {
                filePath = "src/F.kt"
                patches =
                    listOf(
                        // beyond doc
                        PatchFile.Patch(fromLine = 10, toLine = 10, newContent = "TEN"),
                        PatchFile.Patch(fromLine = 2, toLine = 2, newContent = "Y", expectedText = "y"),
                    )
                stopOnMismatch = false
                validateAfterUpdate = false
            }
        val res = tool.execute(project)
        assertTrue(res.contains("Patched 1 range(s)"), "Only valid patch should apply: $res")
        val lines = doc.text.split("\n")
        assertEquals("Y", lines[1])
    }
}
