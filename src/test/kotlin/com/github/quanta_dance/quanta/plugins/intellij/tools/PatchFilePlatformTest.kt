// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertTrue

class PatchFilePlatformTest : BasePlatformTestCase() {
    private fun normalizedSha256(text: String): String {
        val norm = text.replace("\r\n", "\n").replace("\r", "\n")
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(norm.toByteArray()).joinToString("") { b -> "%02x".format(b) }
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

    private fun commitAndSave(psi: PsiFile) {
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)
        if (doc != null) {
            PsiDocumentManager.getInstance(project).commitDocument(doc)
            FileDocumentManager.getInstance().saveDocument(doc)
        }
    }

    fun testApplySinglePatch() {
        val initial =
            """
            |line1
            |line2
            |line3
            """.trimMargin()
        val psi = createUnderProject("src/A.kt", initial)
        commitAndSave(psi)
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)!!

        val tool =
            com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile().apply {
                filePath = "src/A.kt"
                patches =
                    listOf(
                        com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile.Patch(fromLine = 2, toLine = 2, newContent = "LINE2", expectedText = "line2"),
                    )
                expectedFileHashSha256 = normalizedSha256(doc.text)
                validateAfterUpdate = false
            }
        val res = tool.execute(project)
        commitAndSave(psi)
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
        commitAndSave(psi)
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)!!

        val tool =
            com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile().apply {
                filePath = "src/B.kt"
                patches =
                    listOf(
                        com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile.Patch(fromLine = 2, toLine = 2, newContent = "B", expectedText = "MISMATCH"),
                        com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile.Patch(fromLine = 3, toLine = 3, newContent = "C"),
                    )
                expectedFileHashSha256 = normalizedSha256(doc.text)
                stopOnMismatch = true
                validateAfterUpdate = false
            }
        val res = tool.execute(project)
        commitAndSave(psi)
        assertTrue(res.contains("mismatch") && res.contains("Aborted"), "Should report mismatch and abort: $res")
        assertEquals(initial, doc.text)
    }

    fun testContentHashMismatchAbortsWhenNotAllowed() {
        val initial =
            """
            |x
            |y
            |z
            """.trimMargin()
        val psi = createUnderProject("src/C.kt", initial)
        commitAndSave(psi)
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)!!

        val tool =
            com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile().apply {
                filePath = "src/C.kt"
                patches =
                    listOf(
                        com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile.Patch(fromLine = 1, toLine = 1, newContent = "X", expectedText = "x"),
                    )
                expectedFileHashSha256 = normalizedSha256("different content to force mismatch")
                allowProceedIfGuardsMatch = false
                validateAfterUpdate = false
            }
        val res = tool.execute(project)
        commitAndSave(psi)
        assertTrue(res.contains("Content hash mismatch"), "Should detect content hash mismatch: $res")
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
        commitAndSave(psi)
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)!!

        val tool =
            com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile().apply {
                filePath = "src/D.kt"
                patches =
                    listOf(
                        // mismatch
                        com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile.Patch(fromLine = 2, toLine = 2, newContent = "TWO", expectedText = "MIS"),
                        // ok
                        com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile.Patch(fromLine = 4, toLine = 4, newContent = "FOUR", expectedText = "four"),
                    )
                expectedFileHashSha256 = normalizedSha256(doc.text)
                stopOnMismatch = false
                validateAfterUpdate = false
            }
        val res = tool.execute(project)
        commitAndSave(psi)
        assertTrue(res.contains("Patched 1 range(s)"), "Should apply only the second patch: $res")
        val lines = doc.text.split("\n")
        assertEquals("FOUR", lines[3])
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
        commitAndSave(psi)
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)!!

        val tool =
            com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile().apply {
                filePath = "src/E.kt"
                patches =
                    listOf(
                        com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile.Patch(fromLine = 3, toLine = 4, newContent = "CC\nDD", expectedText = "c\nd"),
                        com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile.Patch(fromLine = 1, toLine = 1, newContent = "AA", expectedText = "a"),
                    )
                expectedFileHashSha256 = normalizedSha256(doc.text)
                stopOnMismatch = true
                validateAfterUpdate = false
            }
        val res = tool.execute(project)
        commitAndSave(psi)
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
        commitAndSave(psi)
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi)!!

        val tool =
            com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile().apply {
                filePath = "src/F.kt"
                patches =
                    listOf(
                        // beyond doc
                        com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile.Patch(fromLine = 10, toLine = 10, newContent = "TEN"),
                        com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile.Patch(fromLine = 2, toLine = 2, newContent = "Y", expectedText = "y"),
                    )
                expectedFileHashSha256 = normalizedSha256(doc.text)
                stopOnMismatch = false
                validateAfterUpdate = false
            }
        val res = tool.execute(project)
        commitAndSave(psi)
        assertTrue(res.contains("Patched 1 range(s)"), "Only valid patch should apply: $res")
        val lines = doc.text.split("\n")
        assertEquals("Y", lines[1])
    }
}
