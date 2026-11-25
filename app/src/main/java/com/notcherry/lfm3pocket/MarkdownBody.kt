package com.notcherry.lfm3pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notcherry.lfm3pocket.ui.theme.StillwaterMono
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasTypography

/**
 * Compose renderer for [MarkdownParser] subset (U07).
 * Uses Atlas tokens ([atlasColors] / [atlasTypography]) for crisp Stillwater typography and code blocks.
 */
@Composable
fun MarkdownBody(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    val linkColor = atlas.accent
    val codeBg = atlas.codeBg
    val bodyColor = atlas.ink
    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            val top = if (index == 0) 0.dp else 10.dp
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }
                    Text(
                        text = annotatedInlines(block.inlines, linkColor, codeBg, atlas.inkSoft),
                        style = style,
                        color = bodyColor,
                        modifier = Modifier.padding(top = top),
                    )
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = annotatedInlines(block.inlines, linkColor, codeBg, atlas.inkSoft),
                        style = type.body,
                        color = bodyColor,
                        modifier = Modifier.padding(top = top),
                    )
                }
                is MdBlock.BulletList -> {
                    Column(
                        Modifier.padding(top = top),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        block.items.forEach { item ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    text = "•  ",
                                    style = type.body,
                                    color = atlas.accent,
                                )
                                Text(
                                    text = annotatedInlines(item, linkColor, codeBg, atlas.inkSoft),
                                    style = type.body,
                                    color = bodyColor,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                is MdBlock.OrderedList -> {
                    Column(
                        Modifier.padding(top = top),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        block.items.forEachIndexed { i, item ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    text = "${i + 1}. ",
                                    style = type.mono,
                                    color = atlas.inkMuted,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                                Text(
                                    text = annotatedInlines(item, linkColor, codeBg, atlas.inkSoft),
                                    style = type.body,
                                    color = bodyColor,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                is MdBlock.CodeFence -> {
                    SelectionContainer {
                        Text(
                            text = block.code,
                            color = atlas.inkSoft,
                            style = type.mono.copy(
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                            ),
                            modifier = Modifier
                                .padding(top = top)
                                .fillMaxWidth()
                                .background(codeBg, MaterialTheme.shapes.medium)
                                .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun annotatedInlines(
    inlines: List<MdInline>,
    linkColor: Color,
    codeBg: Color,
    codeTextColor: Color,
) = buildAnnotatedString {
    fun emit(nodes: List<MdInline>) {
        for (node in nodes) {
            when (node) {
                is MdInline.Text -> append(node.value)
                is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { emit(node.children) }
                is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { emit(node.children) }
                is MdInline.Code -> withStyle(
                    SpanStyle(
                        fontFamily = StillwaterMono,
                        fontSize = 14.sp,
                        background = codeBg,
                        color = codeTextColor,
                    ),
                ) { append(" ${node.value} ") }
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
