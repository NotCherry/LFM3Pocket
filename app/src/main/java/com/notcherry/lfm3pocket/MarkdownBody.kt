package com.notcherry.lfm3pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.sp

/**
 * Compose renderer for [MarkdownParser] subset (U07).
 * Uses MaterialTheme colors so it works before/with Atlas [PocketTheme].
 */
@Composable
fun MarkdownBody(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val bodyColor = MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            val top = if (index == 0) 0.dp else 8.dp
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }
                    Text(
                        text = annotatedInlines(block.inlines, linkColor),
                        style = style,
                        color = bodyColor,
                        modifier = Modifier.padding(top = top),
                    )
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = annotatedInlines(block.inlines, linkColor),
                        style = MaterialTheme.typography.bodyLarge,
                        color = bodyColor,
                        modifier = Modifier.padding(top = top),
                    )
                }
                is MdBlock.BulletList -> {
                    Column(Modifier.padding(top = top)) {
                        block.items.forEach { item ->
                            Text(
                                text = buildAnnotatedString {
                                    append("• ")
                                    append(annotatedInlines(item, linkColor))
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = bodyColor,
                            )
                        }
                    }
                }
                is MdBlock.OrderedList -> {
                    Column(Modifier.padding(top = top)) {
                        block.items.forEachIndexed { i, item ->
                            Text(
                                text = buildAnnotatedString {
                                    append("${i + 1}. ")
                                    append(annotatedInlines(item, linkColor))
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = bodyColor,
                            )
                        }
                    }
                }
                is MdBlock.CodeFence -> {
                    SelectionContainer {
                        Text(
                            text = block.code,
                            color = bodyColor,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                            ),
                            modifier = Modifier
                                .padding(top = top)
                                .fillMaxWidth()
                                .background(codeBg)
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun annotatedInlines(inlines: List<MdInline>, linkColor: Color) = buildAnnotatedString {
    fun emit(nodes: List<MdInline>) {
        for (node in nodes) {
            when (node) {
                is MdInline.Text -> append(node.value)
                is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { emit(node.children) }
                is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { emit(node.children) }
                is MdInline.Code -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                ) { append(node.value) }
                is MdInline.Link -> {
                    withLink(
                        LinkAnnotation.Url(
                            node.url,
                            TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                    ) { append(node.text) }
                }
            }
        }
    }
    emit(inlines)
}
