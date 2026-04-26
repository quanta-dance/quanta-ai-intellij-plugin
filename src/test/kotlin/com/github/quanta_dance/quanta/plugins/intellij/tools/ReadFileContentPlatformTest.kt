// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.ReadFileContent
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import kotlin.test.assertTrue

class ReadFileContentPlatformTest : BasePlatformTestCase() {
    private fun createUnderProject(
        relativePath: String,
        content: String,
    ): File {
        val base = project.basePath ?: error("Project basePath is null in platform test")
        val io = File(base, relativePath)
        io.parentFile.mkdirs()
        io.writeText(content)
        // Ensure it is visible to VFS
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)
            ?: error("Failed to create VFS file for $relativePath")
        return io
    }

    fun testRangeValidationErrors() {
        createUnderProject("src/read/A.txt", "one\ntwo\n")

        val tool =
            ReadFileContent().apply {
                filePath = "src/read/A.txt"
                fromLine = 0
            }
        val res = tool.execute(project)
        assertTrue(res.content.isBlank(), "content should be blank on error")
        assertEquals("fromLine must be >= 1", res.error)
    }

    fun testSliceFromToWithLineNumbers() {
        createUnderProject("src/read/B.txt", (1..10).joinToString("\n") { "line$it" })

        val tool =
            ReadFileContent().apply {
                filePath = "src/read/B.txt"
                includeLineNumbers = true
                fromLine = 3
                toLine = 5
                maxChars = 50_000
            }
        val res = tool.execute(project)
        assertEquals("", res.error)

        val expected =
            """
            00003 line3
            00004 line4
            00005 line5
            """.trimIndent()
        assertEquals(expected, res.content.trimEnd())
    }

    fun testSliceToLineOnlyDefaultsFromLineToOne() {
        createUnderProject("src/read/C.txt", "a\nb\nc\n")

        val tool =
            ReadFileContent().apply {
                filePath = "src/read/C.txt"
                toLine = 2
                maxChars = 50_000
            }
        val res = tool.execute(project)
        assertEquals("", res.error)
        assertEquals("a\nb", res.content)
    }

    fun testSliceThenTruncateHeadKeepsCorrectStartLine() {
        val longLine = "x".repeat(200)
        val content = (1..20).joinToString("\n") { "$it:$longLine" }
        createUnderProject("src/read/D.txt", content)

        val tool =
            ReadFileContent().apply {
                filePath = "src/read/D.txt"
                includeLineNumbers = true
                fromLine = 10
                toLine = 20
                strategy = "head"
                // Force truncation even after slicing
                maxChars = 250
            }
        val res = tool.execute(project)
        assertEquals("", res.error)
        // Should start from line 10 even after truncation.
        assertTrue(res.content.startsWith("00010 "), "Expected content to start with original line 10, got: ${res.content.take(20)}")
    }
}
