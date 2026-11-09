package com.battlesbudz.jarvis.v2.chat

import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** One styled run of inline text in an assistant reply. */
internal data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/** One list row: its rendered marker ("•", "1.", …), nesting depth and content. */
internal data class MarkdownListItem(
    val marker: String,
    val depth: Int,
    val spans: List<MarkdownSpan>,
)

internal sealed interface MarkdownBlock {
    data class Paragraph(val spans: List<MarkdownSpan>) : MarkdownBlock
    data class Heading(val level: Int, val spans: List<MarkdownSpan>) : MarkdownBlock
    data class ListBlock(val items: List<MarkdownListItem>) : MarkdownBlock
    data class CodeBlock(val text: String) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/**
 * Projects assistant reply text onto the small block/span model the chat UI
 * renders (issue #20 item 1: replies are written in Markdown but were shown
 * verbatim, asterisks and all). Parsing itself is delegated to the CommonMark
 * parser; this object only walks the parsed tree. Display-only: prompts,
 * stored history and speech keep the raw text (see [AssistantText]), and a
 * streamed partial reply simply parses as whatever is complete so far, with
 * an unclosed emphasis marker degrading to literal text until it closes.
 */
internal object AssistantMarkdown {
    private val parser: Parser = Parser.builder().build()

    fun parse(text: String): List<MarkdownBlock> {
        if (text.isBlank()) return emptyList()
        return blocksFrom(parser.parse(text))
    }

    private fun blocksFrom(container: Node): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var child = container.firstChild
        while (child != null) {
            when (child) {
                is Paragraph -> blocks += MarkdownBlock.Paragraph(inlineSpans(child))
                is Heading -> blocks += MarkdownBlock.Heading(child.level, inlineSpans(child))
                is ListBlock -> blocks += MarkdownBlock.ListBlock(flattenList(child, depth = 0))
                is FencedCodeBlock -> blocks += MarkdownBlock.CodeBlock(child.literal.trimEnd('\n'))
                is IndentedCodeBlock -> blocks += MarkdownBlock.CodeBlock(child.literal.trimEnd('\n'))
                is BlockQuote -> blocks += MarkdownBlock.Quote(blocksFrom(child))
                is ThematicBreak -> blocks += MarkdownBlock.Divider
                // Raw HTML has no chat rendering; keep it visible as text,
                // exactly what the previous plain-text rendering showed.
                is HtmlBlock -> child.literal.trim().takeIf { it.isNotEmpty() }?.let {
                    blocks += MarkdownBlock.Paragraph(listOf(MarkdownSpan(it)))
                }
                else -> Unit
            }
            child = child.next
        }
        return blocks
    }

    private fun flattenList(list: ListBlock, depth: Int): List<MarkdownListItem> {
        val items = mutableListOf<MarkdownListItem>()
        val start = (list as? OrderedList)?.startNumber ?: 1
        var index = 0
        var child = list.firstChild
        while (child != null) {
            if (child is ListItem) {
                val marker = if (list is OrderedList) "${start + index}." else "•"
                val spans = mutableListOf<MarkdownSpan>()
                val nested = mutableListOf<MarkdownListItem>()
                var part = child.firstChild
                while (part != null) {
                    when (part) {
                        is Paragraph -> spans += inlineSpans(part)
                        is ListBlock -> nested += flattenList(part, depth + 1)
                        is FencedCodeBlock -> spans += MarkdownSpan(part.literal.trimEnd('\n'), code = true)
                        is IndentedCodeBlock -> spans += MarkdownSpan(part.literal.trimEnd('\n'), code = true)
                        is BlockQuote -> part.firstChild?.let { if (it is Paragraph) spans += inlineSpans(it) }
                        else -> Unit
                    }
                    part = part.next
                }
                items += MarkdownListItem(marker, depth, mergeAdjacent(spans))
                items += nested
                index++
            }
            child = child.next
        }
        return items
    }

    private fun inlineSpans(container: Node): List<MarkdownSpan> {
        val spans = mutableListOf<MarkdownSpan>()
        var child = container.firstChild
        while (child != null) {
            collectInline(child, bold = false, italic = false, link = null, out = spans)
            child = child.next
        }
        return mergeAdjacent(spans)
    }

    private fun collectInline(
        node: Node,
        bold: Boolean,
        italic: Boolean,
        link: String?,
        out: MutableList<MarkdownSpan>,
    ) {
        when (node) {
            is Text -> out += MarkdownSpan(node.literal, bold, italic, link = link)
            is Code -> out += MarkdownSpan(node.literal, bold, italic, code = true, link = link)
            is StrongEmphasis -> collectChildren(node, bold = true, italic = italic, link = link, out = out)
            is Emphasis -> collectChildren(node, bold = bold, italic = true, link = link, out = out)
            is Link -> collectChildren(node, bold = bold, italic = italic,
                link = node.destination.takeIf { it.isNotBlank() }, out = out)
            // No image loading in chat: the alt text stands in for the image.
            is Image -> collectChildren(node, bold = bold, italic = italic, link = link, out = out)
            is SoftLineBreak -> out += MarkdownSpan(" ", bold, italic, link = link)
            is HardLineBreak -> out += MarkdownSpan("\n", bold, italic, link = link)
            is HtmlInline -> out += MarkdownSpan(node.literal, bold, italic, link = link)
            else -> collectChildren(node, bold, italic, link, out)
        }
    }

    private fun collectChildren(
        node: Node,
        bold: Boolean,
        italic: Boolean,
        link: String?,
        out: MutableList<MarkdownSpan>,
    ) {
        var child = node.firstChild
        while (child != null) {
            collectInline(child, bold, italic, link, out)
            child = child.next
        }
    }

    private fun mergeAdjacent(spans: List<MarkdownSpan>): List<MarkdownSpan> {
        if (spans.size < 2) return spans.filter { it.text.isNotEmpty() }
        val merged = mutableListOf<MarkdownSpan>()
        for (span in spans) {
            if (span.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.bold == span.bold && last.italic == span.italic &&
                last.code == span.code && last.link == span.link
            ) {
                merged[merged.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                merged += span
            }
        }
        return merged
    }
}
