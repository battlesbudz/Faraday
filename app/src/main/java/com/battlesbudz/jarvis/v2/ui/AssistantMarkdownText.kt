package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.chat.AssistantMarkdown
import com.battlesbudz.jarvis.v2.chat.MarkdownBlock
import com.battlesbudz.jarvis.v2.chat.MarkdownSpan

/**
 * Renders an assistant reply's Markdown (issue #20 item 1) from the
 * [AssistantMarkdown] block model. Material3 [Text] throughout, so the
 * surrounding SelectionContainer keeps working exactly as it did for the
 * previous plain-text rendering. User messages are not rendered here; they
 * keep the plain rendering they have always had.
 */
@Composable
internal fun AssistantMarkdownText(text: String, spoken: Boolean, modifier: Modifier = Modifier) {
    val blocks = remember(text) { AssistantMarkdown.parse(text) }
    val baseFontStyle = if (spoken) FontStyle.Italic else FontStyle.Normal
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> MarkdownBlockContent(block, baseFontStyle) }
    }
}

@Composable
private fun MarkdownBlockContent(block: MarkdownBlock, baseFontStyle: FontStyle) {
    when (block) {
        is MarkdownBlock.Paragraph -> Text(block.spans.toAnnotatedString(), fontStyle = baseFontStyle)
        is MarkdownBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleMedium
                2 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.bodyLarge
            }.copy(fontWeight = FontWeight.Bold)
            Text(block.spans.toAnnotatedString(), style = style, fontStyle = baseFontStyle)
        }
        is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item ->
                Row {
                    Text(item.marker,
                        modifier = Modifier.width((30 + item.depth * 16).dp),
                        fontStyle = baseFontStyle)
                    Text(item.spans.toAnnotatedString(),
                        modifier = Modifier.weight(1f),
                        fontStyle = baseFontStyle)
                }
            }
        }
        is MarkdownBlock.CodeBlock -> Text(block.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                .padding(10.dp))
        is MarkdownBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.fillMaxHeight().width(3.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.blocks.forEach { MarkdownBlockContent(it, baseFontStyle) }
            }
        }
        MarkdownBlock.Divider -> HorizontalDivider()
    }
}

@Composable
private fun List<MarkdownSpan>.toAnnotatedString(): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(this, codeBackground, linkColor) {
        buildAnnotatedString {
            for (span in this@toAnnotatedString) {
                val style = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) codeBackground else Color.Unspecified,
                )
                val link = span.link
                if (link != null) {
                    withLink(
                        LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(
                            color = linkColor, textDecoration = TextDecoration.Underline)))
                    ) { withStyle(style) { append(span.text) } }
                } else {
                    withStyle(style) { append(span.text) }
                }
            }
        }
    }
}
