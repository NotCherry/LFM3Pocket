package com.notcherry.lfm3pocket

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.notcherry.lfm3pocket.ui.theme.StillwaterControls
import com.notcherry.lfm3pocket.ui.theme.StillwaterMono
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasSpacing
import com.notcherry.lfm3pocket.ui.theme.atlasTypography
import kotlinx.coroutines.delay

internal data class UserAttachmentBadge(val kind: String, val label: String)
internal data class ParsedUserTurn(val body: String, val attachments: List<UserAttachmentBadge>)

internal fun parseUserTurnAttachments(raw: String): ParsedUserTurn {
    if (raw.isEmpty()) return ParsedUserTurn("", emptyList())
    val bodyLines = ArrayList<String>()
    val badges = ArrayList<UserAttachmentBadge>()
    for (line in raw.split('\n')) {
        val trimmed = line.trim()
        when {
            trimmed == "[photo attached]" -> badges.add(UserAttachmentBadge("PHOTO", "Photo"))
            trimmed == "[photo context]" -> badges.add(UserAttachmentBadge("PHOTO", "Photo context"))
            trimmed == "[voice recording attached]" -> badges.add(UserAttachmentBadge("AUDIO", "Voice recording"))
            trimmed.startsWith("[documents attached:") && trimmed.endsWith("]") -> {
                val inner = trimmed.removePrefix("[documents attached:").removeSuffix("]").trim()
                if (inner.isNotEmpty()) {
                    inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
                        badges.add(UserAttachmentBadge("DOC", name))
                    }
                }
            }
            else -> bodyLines.add(line)
        }
    }
    return ParsedUserTurn(bodyLines.joinToString("\n").trimEnd(), badges)
}

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
    modelReady: Boolean = true,
    onOpenSettings: () -> Unit = {},
    onSuggest: (String) -> Unit = {},
    onRetryInterrupted: (String) -> Unit = {},
    onOpenAttachment: (ChatSnapshot.AttachmentRef) -> Unit = {},
) {
    val scroll = rememberScrollState()
    val space = MaterialTheme.atlasSpacing
    LaunchedEffect(turns.size, turns.lastOrNull()?.text, turns.lastOrNull()?.streaming) {
        if (autoScroll && turns.isNotEmpty()) scroll.scrollTo(scroll.maxValue)
    }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(scroll),
    ) {
        if (turns.isEmpty()) {
            EmptyConversation(
                packageName = packageName,
                modelReady = modelReady,
                onOpenSettings = onOpenSettings,
                onSuggest = onSuggest,
            )
        } else {
            turns.forEachIndexed { index, turn ->
                ChatTurn(
                    turn = turn,
                    packageName = packageName,
                    userMessageTag = userMessageTag,
                    assistantMessageTag = assistantMessageTag,
                    onRetryInterrupted = onRetryInterrupted,
                    onOpenAttachment = onOpenAttachment,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = space.space2, vertical = space.turnY),
                )
                if (index < turns.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.atlasColors.border.copy(alpha = 0.5f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = space.space2),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatTurn(
    turn: ChatSnapshot.Turn,
    packageName: String,
    modifier: Modifier = Modifier,
    userMessageTag: String? = null,
    assistantMessageTag: String? = null,
    onRetryInterrupted: (String) -> Unit = {},
    onOpenAttachment: (ChatSnapshot.AttachmentRef) -> Unit = {},
) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    val space = MaterialTheme.atlasSpacing
    val isUser = turn.role == "user"
    val roleLabel = if (isUser) stringResource(R.string.chat_role_you) else stringResource(R.string.app_name)
    val bodyTag = when {
        isUser && userMessageTag != null -> "$packageName:id/$userMessageTag"
        !isUser && assistantMessageTag != null -> "$packageName:id/$assistantMessageTag"
        else -> null
    }
    Column(modifier) {
        Text(
            roleLabel,
            style = type.label,
            color = if (isUser) atlas.inkMuted else atlas.accent,
        )
        Spacer(Modifier.height(space.space2))
        if (isUser) {
            val parsed = remember(turn.text) { parseUserTurnAttachments(turn.text) }
            val attachmentRefs = remember(turn.attachments, parsed.attachments) {
                if (turn.attachments.isNotEmpty()) {
                    turn.attachments
                } else {
                    parsed.attachments.map { badge ->
                        ChatSnapshot.AttachmentRef(badge.kind, badge.label, "", "", "")
                    }
                }
            }
            SelectionContainer {
                Text(
                    text = parsed.body.ifBlank { turn.text },
                    style = type.body,
                    color = atlas.inkSoft,
                    modifier = Modifier
                        .drawBehind {
                            drawLine(
                                color = atlas.accent,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = 2.dp.toPx(),
                            )
                        }
                        .padding(start = space.space4)
                        .then(if (bodyTag != null) Modifier.testTag(bodyTag) else Modifier)
                        // History E2E uses By.desc(user text) for voice/transcript exposure.
                        .semantics { contentDescription = turn.text },
                )
            }
            if (attachmentRefs.isNotEmpty()) {
                Spacer(Modifier.height(space.space2))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = space.space4),
                    horizontalArrangement = Arrangement.spacedBy(space.space2),
                    verticalArrangement = Arrangement.spacedBy(space.space1),
                ) {
                    attachmentRefs.forEach { ref ->
                        val openable = ref.path.isNotEmpty() || ref.sourceUri.isNotEmpty() || ref.fallbackText.isNotEmpty()
                        Row(
                            modifier = Modifier
                                .background(atlas.surfaceRaised, MaterialTheme.shapes.small)
                                .border(1.dp, if (openable) atlas.accent.copy(alpha = 0.6f) else atlas.borderStrong, MaterialTheme.shapes.small)
                                .clickable { onOpenAttachment(ref) }
                                .padding(horizontal = space.space2, vertical = 5.dp)
                                .testTag("$packageName:id/turn_attachment_chip"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = ref.kind,
                                style = type.mono,
                                color = atlas.accent,
                            )
                            Text(
                                text = " · ${ref.label}" + if (openable) " ↗" else "",
                                style = type.bodySmall,
                                color = atlas.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        } else {
            // Always place collapsible reasoning above the answer across all viewports so
            // assistant text and code blocks get 100% reading width (no awkward side panel).
            if (!turn.reasoning.isNullOrBlank()) {
                ReasoningRail(
                    reasoning = turn.reasoning!!,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = space.space3),
                )
            }
            AssistantAnswerColumn(
                turn = turn,
                packageName = packageName,
                bodyTag = bodyTag,
                onRetryInterrupted = onRetryInterrupted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AssistantAnswerColumn(
    turn: ChatSnapshot.Turn,
    packageName: String,
    bodyTag: String?,
    onRetryInterrupted: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    val space = MaterialTheme.atlasSpacing
    Column(modifier) {
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
            Spacer(Modifier.height(space.space2))
            SourceLinks(turn.sources)
        }
        if (turn.interrupted && !turn.turnId.isNullOrBlank()) {
            Spacer(Modifier.height(space.space2))
            val retryLabel = stringResource(R.string.chat_interrupted_retry)
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .background(atlas.surfaceRaised, MaterialTheme.shapes.medium)
                    .border(1.dp, atlas.borderStrong, MaterialTheme.shapes.medium)
                    .clickable { onRetryInterrupted(turn.turnId!!) }
                    .padding(horizontal = space.space3, vertical = space.space2)
                    .testTag("$packageName:id/chat_retry_interrupted")
                    .semantics { contentDescription = retryLabel },
            ) {
                Text(
                    text = retryLabel,
                    style = type.label,
                    color = atlas.accent,
                )
            }
        }
    }
}

@Composable
fun ReasoningRail(reasoning: String, modifier: Modifier = Modifier) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    val space = MaterialTheme.atlasSpacing
    var expanded by remember { mutableStateOf(false) }
    val preview = reasoning.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(120)
    Column(
        modifier
            .background(atlas.surfaceRaised, MaterialTheme.shapes.medium)
            .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
            .clickable { expanded = !expanded }
            .heightIn(min = 48.dp)
            .padding(horizontal = space.space4, vertical = space.space3),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (expanded) "▾ " else "▸ ",
                    style = type.bodySmall,
                    color = atlas.accent,
                )
                Text(
                    text = stringResource(R.string.chat_reasoning),
                    style = type.bodySmall,
                    color = atlas.ink,
                )
            }
            if (!expanded && preview.isNotBlank()) {
                Spacer(Modifier.width(space.space3))
                Text(
                    text = preview,
                    style = type.bodySmall,
                    color = atlas.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(
                    color = atlas.border,
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = space.space2),
                )
                Text(
                    text = reasoning,
                    style = type.bodySmall,
                    color = atlas.inkSoft,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyConversation(
    packageName: String,
    modelReady: Boolean,
    onOpenSettings: () -> Unit,
    onSuggest: (String) -> Unit,
) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    val space = MaterialTheme.atlasSpacing
    Column(Modifier.padding(horizontal = space.space2, vertical = space.space4)) {
        if (!modelReady) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(atlas.surfaceRaised, MaterialTheme.shapes.medium)
                    .border(1.dp, atlas.borderStrong, MaterialTheme.shapes.medium)
                    .padding(space.space4),
            ) {
                Text(
                    text = stringResource(R.string.chat_empty_no_model_title),
                    style = type.title,
                    color = atlas.ink,
                )
                Spacer(Modifier.height(space.space1))
                Text(
                    text = stringResource(R.string.chat_empty_no_model_body),
                    style = type.bodySmall,
                    color = atlas.inkSoft,
                )
                Spacer(Modifier.height(space.space3))
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .heightIn(min = 42.dp)
                        .testTag("$packageName:id/chat_empty_open_settings"),
                    shape = MaterialTheme.shapes.medium,
                    colors = StillwaterControls.buttonColors(),
                ) {
                    Text(stringResource(R.string.chat_empty_open_settings))
                }
            }
            Spacer(Modifier.height(space.space5))
        }
        Text(
            text = stringResource(R.string.chat_empty_title),
            style = type.headline,
            color = atlas.ink,
        )
        Spacer(Modifier.height(space.space2))
        Text(
            text = stringResource(R.string.chat_empty_subtitle),
            style = type.body,
            color = atlas.inkSoft,
        )
        Spacer(Modifier.height(space.space5))
        val prompts = listOf(
            stringResource(R.string.chat_suggest_photo_prompt) to stringResource(R.string.chat_suggest_photo_hint),
            stringResource(R.string.chat_suggest_text_prompt) to stringResource(R.string.chat_suggest_text_hint),
            stringResource(R.string.chat_suggest_tool_prompt) to stringResource(R.string.chat_suggest_tool_hint),
        )
        prompts.forEach { (prompt, hint) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSuggest(prompt) }
                    .padding(vertical = space.space3),
            ) {
                Text(prompt, style = type.body, color = atlas.ink)
                Text(
                    hint,
                    style = type.bodySmall,
                    color = atlas.inkMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(space.space3))
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
                withStyle(SpanStyle(color = atlas.inkMuted, fontFamily = StillwaterMono)) {
                    append("[${index + 1}] ")
                }
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
            Text(
                text = label,
                style = type.bodySmall,
                color = atlas.inkMuted,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
