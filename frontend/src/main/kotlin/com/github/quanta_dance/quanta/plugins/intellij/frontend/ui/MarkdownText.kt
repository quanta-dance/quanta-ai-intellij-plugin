// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatAppColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

private const val LINK_TAG = "markdown-link"
private val unorderedListPattern = Regex("^\\s*[-+*]\\s+(.+)$")
private val orderedListPattern = Regex("^\\s*\\d+[.)]\\s+(.+)$")
private val headingPattern = Regex("^(#{1,6})\\s+(.+)$")
private val horizontalRulePattern = Regex("^\\s*((\\*\\s*){3,}|(-\\s*){3,}|(_\\s*){3,})$")

internal sealed interface MarkdownBlock {
    data class Paragraph(
        val text: String,
    ) : MarkdownBlock

    data class Heading(
        val level: Int,
        val text: String,
    ) : MarkdownBlock

    data class UnorderedList(
        val items: List<String>,
    ) : MarkdownBlock

    data class OrderedList(
        val items: List<String>,
    ) : MarkdownBlock

    data class BlockQuote(
        val text: String,
    ) : MarkdownBlock

    data class CodeBlock(
        val language: String?,
        val code: String,
    ) : MarkdownBlock

    data class Table(
        val headers: List<String>,
        val alignments: List<MarkdownTableAlignment>,
        val rows: List<List<String>>,
    ) : MarkdownBlock

    data object HorizontalRule : MarkdownBlock
}

internal enum class MarkdownTableAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

@Composable
internal fun markdownText(content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        parseMarkdownBlocks(content).forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    markdownInlineText(block.text)
                }

                is MarkdownBlock.Heading -> {
                    markdownHeading(block)
                }

                is MarkdownBlock.UnorderedList -> {
                    markdownList(block.items)
                }

                is MarkdownBlock.OrderedList -> {
                    markdownList(block.items, ordered = true)
                }

                is MarkdownBlock.BlockQuote -> {
                    markdownBlockQuote(block.text)
                }

                is MarkdownBlock.CodeBlock -> {
                    markdownCodeBlock(block)
                }

                is MarkdownBlock.Table -> {
                    markdownTable(block)
                }

                MarkdownBlock.HorizontalRule -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(ChatAppColors.Text.normal.copy(alpha = 0.18f))
                                .padding(top = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun markdownHeading(heading: MarkdownBlock.Heading) {
    val fontSize =
        when (heading.level) {
            1 -> 20.sp
            2 -> 18.sp
            3 -> 16.sp
            else -> 14.sp
        }
    markdownInlineText(
        text = heading.text,
        baseStyle =
            JewelTheme.defaultTextStyle.copy(
                fontSize = fontSize,
                lineHeight = (fontSize.value + 5).sp,
                fontWeight = FontWeight.Bold,
            ),
    )
}

@Composable
private fun markdownList(
    items: List<String>,
    ordered: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEachIndexed { index, item ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = if (ordered) "${index + 1}." else "•",
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp, lineHeight = 18.sp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    markdownInlineText(item)
                }
            }
        }
    }
}

@Composable
private fun markdownBlockQuote(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .background(ChatAppColors.Text.normal.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
                    .padding(vertical = 9.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            markdownInlineText(
                text = text,
                baseStyle =
                    JewelTheme.defaultTextStyle.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontStyle = FontStyle.Italic,
                        color = ChatAppColors.Text.normal.copy(alpha = 0.78f),
                    ),
            )
        }
    }
}

@Composable
private fun markdownCodeBlock(block: MarkdownBlock.CodeBlock) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        block.language?.takeIf { it.isNotBlank() }?.let { language ->
            Text(
                text = language,
                style =
                    JewelTheme.defaultTextStyle.copy(
                        fontSize = 10.sp,
                        color = ChatAppColors.Text.normal.copy(alpha = 0.55f),
                    ),
            )
        }
        Text(
            text = block.code,
            style =
                JewelTheme.defaultTextStyle.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                ),
        )
    }
}

@Composable
private fun markdownTable(table: MarkdownBlock.Table) {
    Column(modifier = Modifier.fillMaxWidth()) {
        markdownTableRow(table.headers, table.alignments, header = true)
        table.rows.forEach { row ->
            markdownTableRow(row, table.alignments)
        }
    }
}

@Composable
private fun markdownTableRow(
    cells: List<String>,
    alignments: List<MarkdownTableAlignment>,
    header: Boolean = false,
) {
    val borderColor = ChatAppColors.Text.normal.copy(alpha = 0.2f)
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        cells.forEachIndexed { index, cell ->
            val textAlign =
                when (alignments.getOrElse(index) { MarkdownTableAlignment.LEFT }) {
                    MarkdownTableAlignment.LEFT -> TextAlign.Start
                    MarkdownTableAlignment.CENTER -> TextAlign.Center
                    MarkdownTableAlignment.RIGHT -> TextAlign.End
                }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(1.dp, borderColor)
                        .background(
                            if (header) ChatAppColors.Text.normal.copy(alpha = 0.07f) else Color.Transparent,
                        ).padding(horizontal = 7.dp, vertical = 5.dp),
            ) {
                markdownInlineText(
                    text = cell,
                    baseStyle =
                        JewelTheme.defaultTextStyle.copy(
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = textAlign,
                        ),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun markdownInlineText(
    text: String,
    baseStyle: TextStyle =
        JewelTheme.defaultTextStyle.copy(
            fontSize = 13.sp,
            lineHeight = 18.sp,
        ),
) {
    val annotatedText = parseMarkdownInline(text)
    val uriHandler = LocalUriHandler.current
    var hoveredLink by remember(annotatedText) { mutableStateOf(false) }
    var textLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    ClickableText(
        text = annotatedText,
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerHoverIcon(
                    icon = if (hoveredLink) PointerIcon.Hand else PointerIcon.Default,
                    overrideDescendants = true,
                ).onPointerEvent(PointerEventType.Move) { event ->
                    val position = event.changes.firstOrNull()?.position
                    hoveredLink = position != null && textLayoutResult?.hasLinkAt(position, annotatedText) == true
                }.onPointerEvent(PointerEventType.Exit) {
                    hoveredLink = false
                },
        style = baseStyle,
        onTextLayout = { textLayoutResult = it },
        onClick = { offset ->
            annotatedText
                .getStringAnnotations(LINK_TAG, offset, offset)
                .firstOrNull()
                ?.item
                ?.let(uriHandler::openUri)
        },
    )
}

private fun androidx.compose.ui.text.TextLayoutResult.hasLinkAt(
    position: Offset,
    text: AnnotatedString,
): Boolean {
    if (position.y < 0f || position.y > size.height || position.x < 0f || position.x > size.width) return false
    val line = getLineForVerticalPosition(position.y)
    val lineStart = minOf(getLineLeft(line), getLineRight(line))
    val lineEnd = maxOf(getLineLeft(line), getLineRight(line))
    if (position.x !in lineStart..lineEnd) return false
    val offset = getOffsetForPosition(position)
    return text.getStringAnnotations(LINK_TAG, offset, offset).isNotEmpty()
}

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index++
            continue
        }

        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val fence = trimmed.take(3)
            val language = trimmed.removePrefix(fence).trim().ifBlank { null }
            val code = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith(fence)) {
                code += lines[index++]
            }
            if (index < lines.size) index++
            blocks += MarkdownBlock.CodeBlock(language, code.joinToString("\n"))
            continue
        }

        headingPattern
            .matchEntire(line)
            ?.let { match ->
                blocks += MarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim())
                index++
                return@let
            }?.also { continue }

        if (horizontalRulePattern.matches(line)) {
            blocks += MarkdownBlock.HorizontalRule
            index++
            continue
        }

        parseTableAt(lines, index)?.let { table ->
            blocks += table.block
            index = table.nextLineIndex
            continue
        }

        unorderedListPattern.matchEntire(line)?.let {
            val items = mutableListOf<String>()
            while (index < lines.size) {
                val item = unorderedListPattern.matchEntire(lines[index])?.groupValues?.get(1) ?: break
                items += item
                index++
            }
            blocks += MarkdownBlock.UnorderedList(items)
            continue
        }

        orderedListPattern.matchEntire(line)?.let {
            val items = mutableListOf<String>()
            while (index < lines.size) {
                val item = orderedListPattern.matchEntire(lines[index])?.groupValues?.get(1) ?: break
                items += item
                index++
            }
            blocks += MarkdownBlock.OrderedList(items)
            continue
        }

        if (trimmed.startsWith(">")) {
            val quote = mutableListOf<String>()
            while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                quote += lines[index].trimStart().removePrefix(">").removePrefix(" ")
                index++
            }
            blocks += MarkdownBlock.BlockQuote(quote.joinToString("\n"))
            continue
        }

        val paragraph = mutableListOf<String>()
        while (index < lines.size && lines[index].isNotBlank() && !startsMarkdownBlock(lines, index)) {
            paragraph += lines[index++]
        }
        if (paragraph.isEmpty()) {
            paragraph += lines[index++]
        }
        blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
    }

    return blocks
}

private data class ParsedTable(
    val block: MarkdownBlock.Table,
    val nextLineIndex: Int,
)

private fun parseTableAt(
    lines: List<String>,
    index: Int,
): ParsedTable? {
    if (index + 1 >= lines.size || '|' !in lines[index]) return null
    val headers = parseTableRow(lines[index])
    val delimiters = parseTableRow(lines[index + 1])
    if (headers.isEmpty() || headers.size != delimiters.size) return null

    val alignments = delimiters.map(::parseTableAlignment)
    if (alignments.any { it == null }) return null

    val rows = mutableListOf<List<String>>()
    var nextIndex = index + 2
    while (nextIndex < lines.size && lines[nextIndex].isNotBlank() && '|' in lines[nextIndex]) {
        val row = parseTableRow(lines[nextIndex])
        rows += List(headers.size) { column -> row.getOrElse(column) { "" } }
        nextIndex++
    }

    return ParsedTable(
        block = MarkdownBlock.Table(headers, alignments.filterNotNull(), rows),
        nextLineIndex = nextIndex,
    )
}

private fun parseTableRow(line: String): List<String> {
    val trimmed = line.trim().removePrefix("|").removeSuffix("|")
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var escaped = false
    trimmed.forEach { character ->
        when {
            escaped -> {
                cell.append('\\').append(character)
                escaped = false
            }

            character == '\\' -> {
                escaped = true
            }

            character == '|' -> {
                cells += cell.toString().trim()
                cell.clear()
            }

            else -> {
                cell.append(character)
            }
        }
    }
    if (escaped) cell.append('\\')
    cells += cell.toString().trim()
    return cells
}

private fun parseTableAlignment(delimiter: String): MarkdownTableAlignment? {
    val value = delimiter.trim()
    if (!Regex("^:?-{3,}:?$").matches(value)) return null
    return when {
        value.startsWith(':') && value.endsWith(':') -> MarkdownTableAlignment.CENTER
        value.endsWith(':') -> MarkdownTableAlignment.RIGHT
        else -> MarkdownTableAlignment.LEFT
    }
}

private fun startsMarkdownBlock(
    lines: List<String>,
    index: Int,
): Boolean {
    val line = lines[index]
    val trimmed = line.trimStart()
    return trimmed.startsWith("```") ||
        trimmed.startsWith("~~~") ||
        horizontalRulePattern.matches(line) ||
        headingPattern.matches(line) ||
        unorderedListPattern.matches(line) ||
        orderedListPattern.matches(line) ||
        trimmed.startsWith(">") ||
        parseTableAt(lines, index) != null
}

internal fun parseMarkdownInline(markdown: String): AnnotatedString =
    buildAnnotatedString {
        appendMarkdown(markdown, detectBareLinks = true)
    }

private fun AnnotatedString.Builder.appendMarkdown(
    markdown: String,
    detectBareLinks: Boolean,
) {
    var index = 0
    while (index < markdown.length) {
        when {
            markdown[index] == '\\' && index + 1 < markdown.length -> {
                append(markdown[index + 1])
                index += 2
            }

            markdown.startsWith("`", index) -> {
                val end = markdown.indexOf('`', index + 1)
                if (end > index) {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.Black.copy(alpha = 0.18f),
                        ),
                    )
                    append(markdown.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(markdown[index++])
                }
            }

            markdown.startsWith("**", index) || markdown.startsWith("__", index) -> {
                val marker = markdown.substring(index, index + 2)
                val canOpen = marker == "**" || canOpenUnderscoreDelimiter(markdown, index, marker.length)
                val end =
                    if (canOpen) findClosingDelimiter(markdown, marker, index + marker.length) else -1
                if (end > index + marker.length) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendMarkdown(markdown.substring(index + marker.length, end), detectBareLinks)
                    pop()
                    index = end + marker.length
                } else {
                    append(marker)
                    index += marker.length
                }
            }

            markdown.startsWith("~~", index) -> {
                val end = markdown.indexOf("~~", index + 2)
                if (end > index + 2) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    appendMarkdown(markdown.substring(index + 2, end), detectBareLinks)
                    pop()
                    index = end + 2
                } else {
                    append("~~")
                    index += 2
                }
            }

            markdown.startsWith("![", index) -> {
                val link = parseInlineLink(markdown, index, image = true)
                if (link != null) {
                    appendLink(link)
                    index = link.endIndex
                } else {
                    append(markdown[index++])
                }
            }

            markdown[index] == '[' -> {
                val link = parseInlineLink(markdown, index, image = false)
                if (link != null) {
                    appendLink(link)
                    index = link.endIndex
                } else {
                    append(markdown[index++])
                }
            }

            detectBareLinks -> {
                val link = parseBareHttpLink(markdown, index)
                if (link != null) {
                    appendLink(link)
                    index = link.endIndex
                } else if (markdown[index] == '*' || markdown[index] == '_') {
                    index = appendEmphasis(markdown, index, detectBareLinks)
                } else {
                    val next = findNextInlineSyntax(markdown, index + 1)
                    append(markdown.substring(index, next))
                    index = next
                }
            }

            markdown[index] == '*' || markdown[index] == '_' -> {
                index = appendEmphasis(markdown, index, detectBareLinks)
            }

            else -> {
                val next =
                    markdown.indexOfAny(charArrayOf('\\', '`', '*', '_', '~', '[', '!'), index + 1).let {
                        if (it < 0) markdown.length else it
                    }
                append(markdown.substring(index, next))
                index = next
            }
        }
    }
}

private fun AnnotatedString.Builder.appendEmphasis(
    markdown: String,
    index: Int,
    detectBareLinks: Boolean,
): Int {
    val marker = markdown[index]
    val canOpen = marker == '*' || canOpenUnderscoreDelimiter(markdown, index, 1)
    val end = if (canOpen) findClosingDelimiter(markdown, marker.toString(), index + 1) else -1
    return if (end > index + 1) {
        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
        appendMarkdown(markdown.substring(index + 1, end), detectBareLinks)
        pop()
        end + 1
    } else {
        append(markdown[index])
        index + 1
    }
}

private fun findNextInlineSyntax(
    markdown: String,
    startIndex: Int,
): Int {
    var index = startIndex
    while (index < markdown.length) {
        if (markdown[index] in "\\`*_~[!" || parseBareHttpLink(markdown, index) != null) return index
        index++
    }
    return markdown.length
}

private fun parseBareHttpLink(
    markdown: String,
    startIndex: Int,
): InlineLink? {
    val schemeLength =
        when {
            markdown.regionMatches(startIndex, "https://", 0, 8, ignoreCase = true) -> 8
            markdown.regionMatches(startIndex, "http://", 0, 7, ignoreCase = true) -> 7
            else -> return null
        }
    val previous = markdown.getOrNull(startIndex - 1)
    if (previous != null && (previous.isLetterOrDigit() || previous in "._-/@")) return null

    var endIndex = startIndex + schemeLength
    while (endIndex < markdown.length && !markdown[endIndex].isWhitespace() && markdown[endIndex] !in "<>\"") {
        endIndex++
    }
    endIndex = trimBareLinkEnd(markdown, startIndex, endIndex)
    if (endIndex <= startIndex + schemeLength) return null

    val url = markdown.substring(startIndex, endIndex)
    return InlineLink(label = url, url = url, endIndex = endIndex)
}

private fun trimBareLinkEnd(
    markdown: String,
    startIndex: Int,
    initialEndIndex: Int,
): Int {
    var endIndex = initialEndIndex
    while (endIndex > startIndex && markdown[endIndex - 1] in ".,;:!?") endIndex--

    val delimiterPairs = listOf('(' to ')', '[' to ']', '{' to '}')
    delimiterPairs.forEach { (opening, closing) ->
        while (
            endIndex > startIndex &&
            markdown[endIndex - 1] == closing &&
            markdown.substring(startIndex, endIndex).count { it == closing } >
            markdown.substring(startIndex, endIndex).count { it == opening }
        ) {
            endIndex--
        }
    }
    return endIndex
}

private fun findClosingDelimiter(
    markdown: String,
    marker: String,
    startIndex: Int,
): Int {
    var candidate = markdown.indexOf(marker, startIndex)
    while (candidate >= 0) {
        if (marker.first() != '_' || canCloseUnderscoreDelimiter(markdown, candidate, marker.length)) {
            return candidate
        }
        candidate = markdown.indexOf(marker, candidate + marker.length)
    }
    return -1
}

private fun canOpenUnderscoreDelimiter(
    markdown: String,
    index: Int,
    length: Int,
): Boolean {
    val previous = markdown.getOrNull(index - 1)
    val next = markdown.getOrNull(index + length) ?: return false
    return !next.isWhitespace() && !(previous?.isLetterOrDigit() == true && next.isLetterOrDigit())
}

private fun canCloseUnderscoreDelimiter(
    markdown: String,
    index: Int,
    length: Int,
): Boolean {
    val previous = markdown.getOrNull(index - 1) ?: return false
    val next = markdown.getOrNull(index + length)
    return !previous.isWhitespace() && !(previous.isLetterOrDigit() && next?.isLetterOrDigit() == true)
}

private data class InlineLink(
    val label: String,
    val url: String,
    val endIndex: Int,
)

private fun parseInlineLink(
    markdown: String,
    startIndex: Int,
    image: Boolean,
): InlineLink? {
    val labelStart = startIndex + if (image) 2 else 1
    val labelEnd = markdown.indexOf(']', labelStart)
    if (labelEnd < labelStart || labelEnd + 1 >= markdown.length || markdown[labelEnd + 1] != '(') return null

    var depth = 1
    var escaped = false
    var cursor = labelEnd + 2
    while (cursor < markdown.length && depth > 0) {
        val character = markdown[cursor]
        when {
            escaped -> escaped = false
            character == '\\' -> escaped = true
            character == '(' -> depth++
            character == ')' -> depth--
        }
        cursor++
    }
    if (depth != 0) return null

    val destination = markdown.substring(labelEnd + 2, cursor - 1).trim()
    val url =
        if (destination.startsWith('<')) {
            destination.substringAfter('<').substringBefore('>')
        } else {
            destination.substringBefore(' ').substringBefore('\t')
        }
    if (url.isBlank()) return null
    return InlineLink(
        label = markdown.substring(labelStart, labelEnd),
        url = url.replace("\\(", "(").replace("\\)", ")"),
        endIndex = cursor,
    )
}

private fun AnnotatedString.Builder.appendLink(link: InlineLink) {
    pushStringAnnotation(LINK_TAG, link.url)
    pushStyle(SpanStyle(color = Color(0xFF69B7FF), textDecoration = TextDecoration.Underline))
    appendMarkdown(link.label, detectBareLinks = false)
    pop()
    pop()
}
