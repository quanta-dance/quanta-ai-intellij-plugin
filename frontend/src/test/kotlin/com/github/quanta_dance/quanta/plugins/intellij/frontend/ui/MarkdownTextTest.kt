// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkdownTextTest {
    @Test
    fun `parses common markdown blocks`() {
        val blocks =
            parseMarkdownBlocks(
                """
                # Result

                A **formatted** answer.

                - first
                - second

                1. one
                2. two

                > important

                ```kotlin
                val answer = 42
                ```

                ---
                """.trimIndent(),
            )

        assertEquals(MarkdownBlock.Heading(1, "Result"), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("A **formatted** answer."), blocks[1])
        assertEquals(MarkdownBlock.UnorderedList(listOf("first", "second")), blocks[2])
        assertEquals(MarkdownBlock.OrderedList(listOf("one", "two")), blocks[3])
        assertEquals(MarkdownBlock.BlockQuote("important"), blocks[4])
        assertEquals(MarkdownBlock.CodeBlock("kotlin", "val answer = 42"), blocks[5])
        assertIs<MarkdownBlock.HorizontalRule>(blocks[6])
    }

    @Test
    fun `renders links and image alternative text as clickable labels`() {
        val rendered =
            parseMarkdownInline(
                "[This is a **link**](<https://example.com/docs> \"Docs\") and " +
                    "![Alternative image text](https://example.com/image_(1).png)",
            )

        assertEquals("This is a link and Alternative image text", rendered.text)
        val links = rendered.getStringAnnotations("markdown-link", 0, rendered.length)
        assertEquals(
            listOf("https://example.com/docs", "https://example.com/image_(1).png"),
            links.map { it.item },
        )
        assertEquals(listOf(0 to 14, 19 to 41), links.map { it.start to it.end })
    }

    @Test
    fun `keeps incomplete image alternative text literal`() {
        val content = "!Alternative image text"

        val rendered = parseMarkdownInline(content)

        assertEquals(content, rendered.text)
        assertEquals(emptyList(), rendered.getStringAnnotations("markdown-link", 0, rendered.length))
    }

    @Test
    fun `parses tables with column alignment and inline markdown`() {
        val blocks =
            parseMarkdownBlocks(
                """
                Intro
                | Name | Status | Notes |
                |:-----|:------:|------:|
                | **API** | Ready | Uses A \| B |
                | UI | In progress | [Details](https://example.com) |
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("Intro"),
                MarkdownBlock.Table(
                    headers = listOf("Name", "Status", "Notes"),
                    alignments =
                        listOf(
                            MarkdownTableAlignment.LEFT,
                            MarkdownTableAlignment.CENTER,
                            MarkdownTableAlignment.RIGHT,
                        ),
                    rows =
                        listOf(
                            listOf("**API**", "Ready", "Uses A \\| B"),
                            listOf("UI", "In progress", "[Details](https://example.com)"),
                        ),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `parses tables without outer pipes and normalizes row widths`() {
        val blocks =
            parseMarkdownBlocks(
                """
                Name | Status
                --- | ---:
                API | Ready
                UI |
                Extra | Complete | ignored
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                MarkdownBlock.Table(
                    headers = listOf("Name", "Status"),
                    alignments = listOf(MarkdownTableAlignment.LEFT, MarkdownTableAlignment.RIGHT),
                    rows =
                        listOf(
                            listOf("API", "Ready"),
                            listOf("UI", ""),
                            listOf("Extra", "Complete"),
                        ),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `removes inline markdown markers while preserving content`() {
        val rendered = parseMarkdownInline("Use **bold**, *italic*, `code`, ~~old~~, and [docs](https://example.com).")

        assertEquals("Use bold, italic, code, old, and docs.", rendered.text)
    }

    @Test
    fun `keeps ordinary plain text unchanged`() {
        val content = "A plain response\nwith a second line."

        assertEquals(listOf(MarkdownBlock.Paragraph(content)), parseMarkdownBlocks(content))
        assertEquals(content, parseMarkdownInline(content).text)
    }

    @Test
    fun `starts a new block without requiring a blank line`() {
        val blocks = parseMarkdownBlocks("Paragraph\n## Heading\n- item")

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("Paragraph"),
                MarkdownBlock.Heading(2, "Heading"),
                MarkdownBlock.UnorderedList(listOf("item")),
            ),
            blocks,
        )
    }

    @Test
    fun `keeps unterminated fenced code as a code block`() {
        val blocks = parseMarkdownBlocks("```\nunfinished")

        assertEquals(listOf(MarkdownBlock.CodeBlock(null, "unfinished")), blocks)
    }
}
