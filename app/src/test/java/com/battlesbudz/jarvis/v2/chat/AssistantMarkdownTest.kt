package com.battlesbudz.jarvis.v2.chat

import org.junit.Assert.*
import org.junit.Test

/**
 * Issue #20 item 1: assistant replies are written in Markdown and were
 * rendered verbatim, so testers saw the asterisks. These pin the projection
 * from reply text to the block/span model the chat UI renders, including the
 * exact list format from the reporter's screenshot.
 */
class AssistantMarkdownTest {
    @Test fun reporterSpecimenParsesAsBulletListWithBoldLeads() {
        val blocks = AssistantMarkdown.parse(
            "* **Renewable Energy Sources:** Utilizing naturally replenishing sources like solar, wind, hydro, geothermal, and biomass.\n" +
                "* **Energy Conversion:** Converting one form of energy into another."
        )
        assertEquals(1, blocks.size)
        val list = blocks.single() as MarkdownBlock.ListBlock
        assertEquals(2, list.items.size)
        assertEquals("•", list.items[0].marker)
        assertEquals(0, list.items[0].depth)
        assertEquals(MarkdownSpan("Renewable Energy Sources:", bold = true), list.items[0].spans.first())
        assertTrue(list.items[0].spans.last().text.contains("Utilizing naturally replenishing sources"))
        assertEquals(MarkdownSpan("Energy Conversion:", bold = true), list.items[1].spans.first())
        assertFalse(blocks.toString().contains("**"))
    }

    @Test fun plainSentenceStaysOneUnstyledParagraph() {
        val blocks = AssistantMarkdown.parse("Free energy is not possible according to thermodynamics.")
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(MarkdownSpan("Free energy is not possible according to thermodynamics.")))),
            blocks
        )
    }

    @Test fun headingLevelsAreKept() {
        val blocks = AssistantMarkdown.parse("# Title\n\n## Section")
        assertEquals(MarkdownBlock.Heading(1, listOf(MarkdownSpan("Title"))), blocks[0])
        assertEquals(MarkdownBlock.Heading(2, listOf(MarkdownSpan("Section"))), blocks[1])
    }

    @Test fun orderedListMarkersCountFromTheListStart() {
        val fromOne = AssistantMarkdown.parse("1. First\n2. Second").single() as MarkdownBlock.ListBlock
        assertEquals(listOf("1.", "2."), fromOne.items.map { it.marker })
        val fromThree = AssistantMarkdown.parse("3. Third\n4. Fourth").single() as MarkdownBlock.ListBlock
        assertEquals(listOf("3.", "4."), fromThree.items.map { it.marker })
    }

    @Test fun nestedBulletsCarryDepth() {
        val list = AssistantMarkdown.parse("- Outer\n  - Inner").single() as MarkdownBlock.ListBlock
        assertEquals(listOf(0, 1), list.items.map { it.depth })
        assertEquals(listOf("Outer", "Inner"), list.items.map { it.spans.single().text })
    }

    @Test fun fencedCodeBlockKeepsLiteralText() {
        val blocks = AssistantMarkdown.parse("Before\n\n```\nval x = 1\nval y = 2\n```")
        assertEquals(MarkdownBlock.CodeBlock("val x = 1\nval y = 2"), blocks[1])
    }

    @Test fun inlineStylesAndLinksAreProjected() {
        val paragraph = AssistantMarkdown.parse(
            "Some **bold** and *italic* and `code` and [a link](https://example.com)."
        ).single() as MarkdownBlock.Paragraph
        assertTrue(paragraph.spans.contains(MarkdownSpan("bold", bold = true)))
        assertTrue(paragraph.spans.contains(MarkdownSpan("italic", italic = true)))
        assertTrue(paragraph.spans.contains(MarkdownSpan("code", code = true)))
        assertTrue(paragraph.spans.contains(MarkdownSpan("a link", link = "https://example.com")))
    }

    @Test fun nestedEmphasisCombinesStyles() {
        val paragraph = AssistantMarkdown.parse("**bold and *italic* end**").single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownSpan("bold and ", bold = true),
                MarkdownSpan("italic", bold = true, italic = true),
                MarkdownSpan(" end", bold = true),
            ),
            paragraph.spans
        )
    }

    @Test fun quoteAndDividerAreKept() {
        val blocks = AssistantMarkdown.parse("> quoted words\n\n---\n\nAfter")
        assertEquals(
            MarkdownBlock.Quote(listOf(MarkdownBlock.Paragraph(listOf(MarkdownSpan("quoted words"))))),
            blocks[0]
        )
        assertEquals(MarkdownBlock.Divider, blocks[1])
        assertTrue(blocks[2] is MarkdownBlock.Paragraph)
    }

    @Test fun blankTextParsesToNoBlocks() {
        assertEquals(emptyList<MarkdownBlock>(), AssistantMarkdown.parse(""))
        assertEquals(emptyList<MarkdownBlock>(), AssistantMarkdown.parse("  \n "))
    }

    @Test fun unclosedEmphasisDegradesToLiteralText() {
        // A streamed reply can pause mid-marker; parsing must not throw and
        // the words must survive as literal text until the marker closes.
        val paragraph = AssistantMarkdown.parse("An unfinished **bold").single() as MarkdownBlock.Paragraph
        assertEquals("An unfinished **bold", paragraph.spans.joinToString("") { it.text })
    }
}
