package com.notcherry.lfm3pocket

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasTypography
import kotlinx.coroutines.delay

/**
 * Atlas chat transcript visuals: open text blocks, Reasoning rail, markdown, sources.
 * Flat Automator text lives on ChatPanel's real [R.id.chat_transcript] TextView (U08/U15).
 */
@Composable
fun ChatTurnList(
    turns: List<ChatSnapshot.Turn>,
    packageName: String,
    modifier: Modifier = Modifier,
    userMessageTag: String? = null,
    assistantMessageTag: String? = null,
    autoScroll: Boolean = true,
    onSuggest: (String) -> Unit = {},
) {
    val scroll = rememberScrollState()
    LaunchedEffect(turns.size, turns.lastOrNull()?.text, turns.lastOrNull()?.streaming) {
        if (autoScroll) scroll.scrollTo(scroll.maxValue)
    }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(scroll),
    ) {
        if (turns.isEmpty()) {
            EmptyConversation(onSuggest)
        } else {
            turns.forEachIndexed { index, turn ->
                ChatTurn(
                    turn = turn,
                    packageName = packageName,
                    userMessageTag = userMessageTag,
                    assistantMessageTag = assistantMessageTag,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
                if (index < turns.lastIndex) {
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
fun ChatTurn(
    turn: ChatSnapshot.Turn,
    packageName: String,
    modifier: Modifier = Modifier,
    userMessageTag: String? = null,
    assistantMessageTag: String? = null,
) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    val isUser = turn.role == "user"
    val roleLabel = if (isUser) "YOU" else "Hanami"
    val bodyTag = when {
        isUser && userMessageTag != null -> "$packageName:id/$userMessageTag"
        !isUser && assistantMessageTag != null -> "$packageName:id/$assistantMessageTag"
        else -> null
    }
    Column(modifier) {
        Text(roleLabel, style = type.label, color = atlas.inkMuted)
        Spacer(Modifier.height(4.dp))
        if (isUser) {
            SelectionContainer {
                Text(
                    text = turn.text,
                    style = type.body,
                    color = ColorSoft,
                    modifier = Modifier
                        .drawBehind {
                            drawLine(
                                color = atlas.accent,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = 2f,
                            )
                        }
                        .padding(start = 12.dp)
                        .then(if (bodyTag != null) Modifier.testTag(bodyTag) else Modifier)
                        // History E2E uses By.desc(user text) for voice/transcript exposure.
                        .semantics { contentDescription = turn.text },
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (!turn.reasoning.isNullOrBlank()) {
                    ReasoningRail(
                        reasoning = turn.reasoning!!,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .widthIn(min = 96.dp, max = 168.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    SelectionContainer {
                        Column(
                            Modifier
                                .then(if (bodyTag != null) Modifier.testTag(bodyTag) else Modifier)
                                .semantics { contentDescription = turn.text },
                        ) {
                            if (turn.text.isNotBlank()) {
                                MarkdownBody(turn.text)
                            }
                            if (turn.streaming) {
                                StreamingCursor()
                            }
                        }
                    }
                    if (!turn.streaming && turn.sources.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SourceLinks(turn.sources)
                    }
                }
            }
        }
    }
}

@Composable
fun ReasoningRail(reasoning: String, modifier: Modifier = Modifier) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    var expanded by remember { mutableStateOf(false) }
    val preview = reasoning.lineSequence().firstOrNull().orEmpty().take(96)
    Column(
        modifier
            .drawBehind {
                drawLine(
                    color = atlas.accent.copy(alpha = 0.55f),
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 2f,
                )
            }
            .padding(start = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Reasoning",
                style = type.label,
                color = atlas.ink,
            )
        }
        if (!expanded && preview.isNotBlank()) {
            Text(
                text = preview,
                style = type.bodySmall,
                color = atlas.inkMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                text = reasoning,
                style = type.mono,
                color = atlas.inkMuted,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

private val ColorSoft = androidx.compose.ui.graphics.Color(0xFFC9C2B4)

@Composable
private fun EmptyConversation(onSuggest: (String) -> Unit) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) {
        Text(
            text = "No conversation yet.",
            style = type.headline,
            color = atlas.ink,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Text, photos, and voice stay on this phone until you opt into search.",
            style = type.body,
            color = ColorSoft,
        )
        Spacer(Modifier.height(20.dp))
        listOf(
            "What colors and shapes are in the image?" to "Photo · on device",
            "Napisz po polsku jedno zdanie o lokalnym przetwarzaniu." to "Text · local",
            "Set a timer for forty five seconds" to "Needs your approval",
        ).forEach { (prompt, hint) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSuggest(prompt) }
                    .padding(vertical = 12.dp),
            ) {
                Text(prompt, style = type.body, color = atlas.ink)
                Text(hint, style = type.bodySmall, color = atlas.inkMuted, modifier = Modifier.padding(top = 3.dp))
                HorizontalDivider(color = atlas.border, thickness = 1.dp)
            }
        }
    }
}

@Composable
fun StreamingCursor(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            visible = !visible
        }
    }
    Text(
        text = FlatTranscriptFormatter.STREAM_CURSOR.toString(),
        style = MaterialTheme.atlasTypography.body,
        color = MaterialTheme.atlasColors.cursor,
        modifier = modifier.alpha(if (visible) 1f else 0.15f),
    )
}

@Composable
private fun SourceLinks(sources: List<ChatSnapshot.Source>) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    Column(Modifier.fillMaxWidth()) {
        sources.forEachIndexed { index, source ->
            val label = buildAnnotatedString {
                append("[${index + 1}] ")
                withLink(
                    LinkAnnotation.Url(
                        source.url,
                        TextLinkStyles(
                            style = SpanStyle(
                                color = atlas.accent,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                    ),
                ) {
                    append(source.title.ifBlank { source.url })
                }
            }
            Text(text = label, style = type.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
