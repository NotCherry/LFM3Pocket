package com.notcherry.lfm3pocket

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notcherry.lfm3pocket.ui.theme.PocketTheme
import com.notcherry.lfm3pocket.ui.theme.StillwaterControls
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasSpacing
import com.notcherry.lfm3pocket.ui.theme.atlasTypography
import java.io.File
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chat shell: Compose Atlas turns plus a real Android [R.id.chat_transcript] TextView so
 * UiAutomator Stage 1 can read flat transcript text without Compose occlusion/alpha issues.
 */
class ChatPanel @JvmOverloads constructor(
    context: Context,
    private val send: Consumer<String>,
    private val stop: Runnable,
    private val clear: Runnable,
    private val retryInterrupted: Consumer<String> = Consumer { },
    private val compact: Runnable = Runnable { },
    private val openSettings: Runnable = Runnable { },
) : LinearLayout(context) {
    private data class Controls(
        val send: Boolean = false,
        val stop: Boolean = false,
        val clear: Boolean = true,
        val compact: Boolean = false,
    )
    private data class ToolCardState(
        val details: String,
        val risk: String,
        val status: String,
        val result: String,
        val pending: Boolean,
        val approve: Runnable?,
        val reject: Runnable?,
    )
    private data class Attachment(val path: String, val caption: String, val onRemove: Runnable)
    private data class DocumentAttachment(val name: String, val onRemove: Runnable)

    private var snapshot by mutableStateOf(ChatSnapshot(emptyList(), "", false))
    private var liveStatus by mutableStateOf("")
    private var searchModeEnabled by mutableStateOf(false)
    private var modelReadyState by mutableStateOf(false)
    private var toolCard by mutableStateOf<ToolCardState?>(null)
    private var attachment by mutableStateOf<Attachment?>(null)
    private var documentAttachments by mutableStateOf<List<DocumentAttachment>>(emptyList())
    private var controls by mutableStateOf(Controls())
    private var pendingDraft by mutableStateOf<String?>(null)

    private val transcriptHost = TextView(context).apply {
        id = R.id.chat_transcript
        visibility = View.VISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setTextColor(Color.TRANSPARENT)
        setBackgroundColor(Color.TRANSPARENT)
        textSize = 14f
        text = FlatTranscriptFormatter.EMPTY_TRANSCRIPT
        contentDescription = FlatTranscriptFormatter.EMPTY_TRANSCRIPT
    }

    private val composeView = object : AbstractComposeView(context) {
        @Composable
        override fun Content() {
            ChatPanelContent()
        }
    }

    init {
        orientation = VERTICAL
        // 1px Automator host above Compose — never occluded, always in the a11y tree.
        val px = (resources.displayMetrics.density).toInt().coerceAtLeast(1)
        addView(transcriptHost, LayoutParams(LayoutParams.MATCH_PARENT, px))
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun publishFlatTranscript(text: String) {
        transcriptHost.text = text
        transcriptHost.contentDescription = text
    }

    fun render(text: String, status: String) {
        publishFlatTranscript(text)
        liveStatus = status
    }

    fun renderSnapshot(next: ChatSnapshot) {
        snapshot = next
        publishFlatTranscript(FlatTranscriptFormatter.formatSnapshot(next))
        liveStatus = next.status
    }

    fun setIdleMode(searchEnabled: Boolean) {
        searchModeEnabled = searchEnabled
    }

    fun setModelReady(ready: Boolean) {
        modelReadyState = ready
    }

    fun clearToolCard() {
        toolCard = null
    }

    fun renderToolCard(
        details: String,
        risk: String,
        status: String,
        result: String,
        pending: Boolean,
        approve: Runnable?,
        reject: Runnable?,
    ) {
        if (details.isBlank() && status.isBlank() && result.isBlank() && !pending) {
            toolCard = null
            return
        }
        toolCard = ToolCardState(details, risk, status, result, pending, approve, reject)
    }

    fun updateControls(canSend: Boolean, canStop: Boolean, canClear: Boolean) {
        updateControls(canSend, canStop, canClear, false)
    }

    fun updateControls(canSend: Boolean, canStop: Boolean, canClear: Boolean, canCompact: Boolean) {
        controls = Controls(canSend, canStop, canClear, canCompact)
        if (canSend || canStop) {
            modelReadyState = true
        }
    }

    fun showImageAttachment(path: String, onRemove: Runnable) {
        showImageAttachment(path, "", onRemove)
    }

    fun showImageAttachment(path: String, caption: String, onRemove: Runnable) {
        attachment = Attachment(path, caption.orEmpty(), onRemove)
    }

    fun clearImageAttachment() {
        attachment = null
    }

    fun setDocumentAttachments(names: List<String>, onRemoveAt: Consumer<Int>) {
        val list = ArrayList<DocumentAttachment>(names.size)
        for (i in names.indices) {
            val index = i
            list.add(DocumentAttachment(names[i]) { onRemoveAt.accept(index) })
        }
        documentAttachments = list
    }

    fun clearDocumentAttachments() {
        documentAttachments = emptyList()
    }

    /** Prefills the composer. Share targets and typed ASK intents use this. */
    fun setDraft(text: String?) {
        if (text.isNullOrBlank()) return
        pendingDraft = text.trim()
    }

    private fun tag(name: String) = "${context.packageName}:id/$name"

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ChatPanelContent() {
        PocketTheme {
            var input by remember { mutableStateOf("") }
            val draft = pendingDraft
            LaunchedEffect(draft) {
                if (draft != null) {
                    input = draft
                    pendingDraft = null
                }
            }
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            val atlas = MaterialTheme.atlasColors
            val type = MaterialTheme.atlasTypography
            val space = MaterialTheme.atlasSpacing
            val idleMode = if (searchModeEnabled) {
                stringResource(R.string.mode_composer_web_search)
            } else {
                stringResource(R.string.mode_local)
            }
            var previewBitmap by remember(attachment?.path) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(attachment?.path) {
                val path = attachment?.path
                previewBitmap = if (path == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        ImageAttachmentFiles.decodePreview(File(path))
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .background(atlas.surface)
                    .semantics { testTagsAsResourceId = true },
            ) {
                toolCard?.let { card ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = space.space3, vertical = space.space2)
                            .background(atlas.surfaceRaised, MaterialTheme.shapes.medium)
                            .border(1.dp, atlas.borderStrong, MaterialTheme.shapes.medium)
                            .padding(space.space4),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.android_action),
                                style = type.title,
                                color = atlas.ink,
                            )
                            if (!card.pending) {
                                TextButton(
                                    onClick = { toolCard = null },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag(tag("tool_dismiss")),
                                    contentPadding = PaddingValues(horizontal = space.space2, vertical = 2.dp),
                                    colors = StillwaterControls.textButtonColors(),
                                ) {
                                    Text(stringResource(R.string.dismiss), style = type.label, color = atlas.inkMuted)
                                }
                            }
                        }
                        Spacer(Modifier.height(space.space1))
                        Text(
                            card.details,
                            style = type.mono,
                            color = atlas.ink,
                            modifier = Modifier.testTag(tag("tool_details")),
                        )
                        if (card.risk.isNotBlank()) {
                            Text(
                                card.risk,
                                style = type.mono,
                                color = riskColor(card.risk, atlas),
                                modifier = Modifier
                                    .padding(top = space.space1)
                                    .testTag(tag("tool_risk")),
                            )
                        }
                        Text(
                            card.status,
                            style = type.bodySmall,
                            color = atlas.inkMuted,
                            modifier = Modifier
                                .padding(top = space.space1)
                                .testTag(tag("tool_status")),
                        )
                        if (card.result.isNotBlank()) {
                            Text(
                                card.result,
                                style = type.mono,
                                color = atlas.ink,
                                modifier = Modifier
                                    .padding(top = space.space1)
                                    .testTag(tag("tool_result")),
                            )
                        }
                        if (card.pending) {
                            Row(
                                Modifier.padding(top = space.space3),
                                horizontalArrangement = Arrangement.spacedBy(space.space2),
                            ) {
                                OutlinedButton(
                                    onClick = { card.reject?.run() },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .heightIn(min = 42.dp)
                                        .testTag(tag("tool_reject")),
                                    shape = MaterialTheme.shapes.medium,
                                    border = BorderStroke(1.dp, atlas.danger),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = atlas.danger,
                                        disabledContentColor = atlas.inkDisabled,
                                    ),
                                ) { Text(stringResource(R.string.reject)) }
                                Button(
                                    onClick = { card.approve?.run() },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .heightIn(min = 42.dp)
                                        .testTag(tag("tool_approve")),
                                    shape = MaterialTheme.shapes.medium,
                                    colors = StillwaterControls.buttonColors(),
                                ) { Text(stringResource(R.string.approve)) }
                            }
                        }
                    }
                }
                ChatTurnList(
                    turns = snapshot.turns,
                    packageName = context.packageName,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    modelReady = modelReadyState,
                    onOpenSettings = { openSettings.run() },
                    onSuggest = { input = it },
                    onRetryInterrupted = { turnId -> retryInterrupted.accept(turnId) },
                )
                if (documentAttachments.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = space.space2, vertical = space.space1)
                            .background(atlas.surfaceRaised, MaterialTheme.shapes.medium)
                            .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
                            .padding(horizontal = space.space3, vertical = space.space2)
                            .testTag(tag("document_attachments")),
                    ) {
                        Text(
                            stringResource(R.string.documents_attached_heading),
                            style = type.label,
                            color = atlas.inkMuted,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        documentAttachments.forEachIndexed { index, doc ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    doc.name,
                                    style = type.mono,
                                    color = atlas.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag(tag("document_attachment_$index")),
                                )
                                TextButton(
                                    onClick = { doc.onRemove.run() },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag(tag("document_remove_$index")),
                                    contentPadding = PaddingValues(horizontal = space.space2, vertical = 2.dp),
                                    colors = StillwaterControls.dangerTextColors(),
                                ) { Text(stringResource(R.string.remove_short), style = type.bodySmall) }
                            }
                        }
                    }
                }
                attachment?.let { image ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = space.space2, vertical = space.space1)
                            .background(atlas.surfaceRaised, MaterialTheme.shapes.medium)
                            .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
                            .padding(space.space2)
                            .testTag(tag("image_attachment")),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        previewBitmap?.let { bitmap ->
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.attached_image_preview_cd),
                                modifier = Modifier
                                    .size(56.dp)
                                    .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
                                    .testTag(tag("image_preview")),
                                contentScale = ContentScale.Crop,
                            )
                        } ?: Text(
                            stringResource(R.string.loading_image_preview),
                            style = type.bodySmall,
                            color = atlas.inkMuted,
                            modifier = Modifier.padding(end = space.space2),
                        )
                        Spacer(Modifier.width(space.space3))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.image_attached),
                                style = type.label,
                                color = atlas.ink,
                            )
                            if (image.caption.isNotBlank()) {
                                Text(
                                    image.caption,
                                    style = type.bodySmall,
                                    color = atlas.inkMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .testTag(tag("image_size_caption")),
                                )
                            }
                        }
                        TextButton(
                            onClick = { image.onRemove.run() },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag(tag("image_remove")),
                            contentPadding = PaddingValues(horizontal = space.space2, vertical = space.space1),
                            colors = StillwaterControls.dangerTextColors(),
                        ) { Text(stringResource(R.string.image_remove), style = type.bodySmall) }
                    }
                }
                val hasClearableContent = snapshot.turns.isNotEmpty() || input.isNotEmpty() ||
                    attachment != null || documentAttachments.isNotEmpty()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(atlas.surfaceRaised, MaterialTheme.shapes.medium)
                        .border(width = 1.dp, color = atlas.border, shape = MaterialTheme.shapes.medium)
                        .padding(horizontal = space.composer, vertical = space.space2),
                ) {
                    ChatLiveStatus(idleMode, liveStatus, context.packageName)
                    Spacer(Modifier.height(space.space2))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = {
                            Text(stringResource(R.string.chat_write_message), color = atlas.inkDisabled)
                        },
                        minLines = 1,
                        maxLines = 5,
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = atlas.ink,
                            unfocusedTextColor = atlas.ink,
                            disabledTextColor = atlas.inkDisabled,
                            focusedBorderColor = atlas.borderStrong,
                            unfocusedBorderColor = atlas.border,
                            cursorColor = atlas.accent,
                            focusedContainerColor = atlas.surface,
                            unfocusedContainerColor = atlas.surface,
                            focusedPlaceholderColor = atlas.inkDisabled,
                            unfocusedPlaceholderColor = atlas.inkDisabled,
                            selectionColors = TextSelectionColors(
                                handleColor = atlas.accent,
                                backgroundColor = atlas.accent.copy(alpha = 0.35f),
                            ),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(tag("chat_input")),
                    )
                    Spacer(Modifier.height(space.space1))
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { compact.run() },
                                enabled = controls.compact && snapshot.turns.isNotEmpty(),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag(tag("chat_compact")),
                                contentPadding = PaddingValues(horizontal = space.space2, vertical = 2.dp),
                                colors = StillwaterControls.textButtonColors(),
                            ) {
                                Text(stringResource(R.string.compact_conversation), style = type.bodySmall)
                            }
                            TextButton(
                                onClick = { clear.run() },
                                enabled = controls.clear,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag(tag("chat_clear")),
                                contentPadding = PaddingValues(horizontal = space.space2, vertical = 2.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (hasClearableContent) atlas.accent else atlas.inkMuted,
                                    disabledContentColor = atlas.inkDisabled,
                                ),
                            ) {
                                Text(stringResource(R.string.clear_conversation), style = type.bodySmall)
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(space.space2),
                        ) {
                            OutlinedButton(
                                onClick = { stop.run() },
                                enabled = controls.stop,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .heightIn(min = 40.dp)
                                    .testTag(tag("chat_stop")),
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(
                                    1.dp,
                                    if (controls.stop) atlas.danger else atlas.border,
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = atlas.danger,
                                    disabledContentColor = atlas.inkMuted,
                                ),
                            ) { Text(stringResource(R.string.stop)) }
                            Button(
                                onClick = {
                                    val text = input
                                    input = ""
                                    focus.clearFocus()
                                    keyboard?.hide()
                                    send.accept(text)
                                },
                                enabled = controls.send,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .heightIn(min = 40.dp)
                                    .testTag(tag("chat_send")),
                                shape = MaterialTheme.shapes.medium,
                                colors = StillwaterControls.buttonColors(),
                            ) { Text(stringResource(R.string.send)) }
                        }
                    }
                }
            }
        }
    }

    private fun riskColor(risk: String, atlas: com.notcherry.lfm3pocket.ui.theme.AtlasColors) =
        when {
            risk.contains("confirm", ignoreCase = true) || risk.contains("danger", ignoreCase = true) -> atlas.danger
            risk.contains("caution", ignoreCase = true) || risk.contains("elevated", ignoreCase = true) -> atlas.warn
            else -> atlas.ok
        }
}
