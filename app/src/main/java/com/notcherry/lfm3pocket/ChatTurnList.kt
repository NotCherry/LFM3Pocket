package com.notcherry.lfm3pocket

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasTypography
import kotlinx.coroutines.delay

/**
 * Atlas chat transcript: open text blocks, left Reasoning rail, markdown answers, steel sources.
 * Flat automator text stays on [flatTestTag] via a zero-height host (U08 / E2E contract).
 */
@Composable
fun ChatTurnList(
    turns: List<ChatSnapshot.Turn>,
    flatTranscript: String,
    packageName: String,
    modifier: Modifier = Modifier,
    flatTestTag: String = "chat_transcript",
    userMessageTag: String? = null,
    assistantMessageTag: String? = null,
    autoScroll: Boolean = true,
) {
    val tag = { name: String -> "$packageName:id/$name" }
    val scroll = rememberScrollState()
    LaunchedEffect(turns.size, turns.lastOrNull()?.text, turns.lastOrNull()?.streaming) {
        if (autoScroll) scroll.scrollTo(scroll.maxValue)
    }
    Column(modifier.fillMaxWidth()) {
        // Real View ID host so UiAutomator By.res(chat_transcript).textContains keeps working.
        AndroidView(
            factory = { context ->
                TextView(context).apply {
                    id = if (flatTestTag == "chat_transcript") R.id.chat_transcript
                    else context.resources.getIdentifier(flatTestTag, "id", packageName)
                    // INVISIBLE (not GONE) so UiAutomator can still read textContains.
                    visibility = View.INVISIBLE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            },
            update = { it.text = flatTranscript },
            modifier = Modifier
                .fillMaxWidth()
                .height(0.dp)
                .testTag(tag(flatTestTag)),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
                .verticalScroll(scroll),
        ) {
            if (turns.isEmpty()) {
                Text(
                    text = "No conversation yet.",
                    style = MaterialTheme.atlasTypography.body,
                    color = MaterialTheme.atlasColors.inkMuted,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                turns.forEachIndexed { index, turn ->
                    ChatTurn(
                        turn = turn,
                        packageName = packageName,
                        userMessageTag = userMessageTag,
                        assistantMessageTag = assistantMessageTag,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    if (index < turns.lastIndex) {
                        Spacer(Modifier.height(4.dp))
                    }
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
    val roleLabel = if (isUser) "YOU" else "LFM3"
    val bodyTag = when {
        isUser && userMessageTag != null -> "$packageName:id/$userMessageTag"
        !isUser && assistantMessageTag != null -> "$packageName:id/$assistantMessageTag"
        else -> null
    }
    Column(modifier) {
        Text(roleLabel, style = type.label, color = atlas.inkMuted)
        Spacer(Modifier.height(6.dp))
        if (isUser) {
            SelectionContainer {
                Text(
                    text = turn.text,
                    style = type.body,
                    color = atlas.ink,
                    modifier = if (bodyTag != null) Modifier.testTag(bodyTag) else Modifier,
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (!turn.reasoning.isNullOrBlank()) {
                    ReasoningRail(
                        reasoning = turn.reasoning!!,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .width(120.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    SelectionContainer {
                        Column(if (bodyTag != null) Modifier.testTag(bodyTag) else Modifier) {
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
    Column(
        modifier
            .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
            .padding(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▾ Reasoning" else "▸ Reasoning",
                style = type.bodySmall,
                color = atlas.inkMuted,
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
