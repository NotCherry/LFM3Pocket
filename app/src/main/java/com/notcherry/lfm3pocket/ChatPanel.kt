package com.notcherry.lfm3pocket

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.notcherry.lfm3pocket.ui.theme.PocketTheme
import com.notcherry.lfm3pocket.ui.theme.StillwaterControls
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasTypography
import java.io.File
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chat shell: Compose Atlas turns plus a real Android [R.id.chat_transcript] TextView so
 * UiAutomator Stage 1 can read flat transcript text without Compose occlusion/alpha issues.
 */
class ChatPanel(
    context: Context,
    private val send: Consumer<String>,
    private val stop: Runnable,
    private val clear: Runnable,
) : LinearLayout(context) {
    private data class Controls(val send: Boolean = false, val stop: Boolean = false, val clear: Boolean = true)
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

    private var snapshot by mutableStateOf(ChatSnapshot(emptyList(), "", false))
    private var liveStatus by mutableStateOf("")
    private var idleMode by mutableStateOf("Mode · Local / offline")
    private var toolCard by mutableStateOf<ToolCardState?>(null)
    private var attachment by mutableStateOf<Attachment?>(null)
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
        idleMode = if (searchEnabled) "Mode · Web search + local model" else "Mode · Local / offline"
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
        toolCard = ToolCardState(details, risk, status, result, pending, approve, reject)
    }

    fun updateControls(canSend: Boolean, canStop: Boolean, canClear: Boolean) {
        controls = Controls(canSend, canStop, canClear)
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

    /** Prefills the composer. Share targets and typed ASK intents use this. */
    fun setDraft(text: String?) {
        if (text.isNullOrBlank()) return
        pendingDraft = text.trim()
    }

    private fun tag(name: String) = "${context.packageName}:id/$name"

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
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .background(atlas.surfaceRaised, MaterialTheme.shapes.large)
                            .border(1.dp, atlas.border, MaterialTheme.shapes.large)
                            .padding(12.dp),
                    ) {
                        Text("Android action", style = type.title, color = atlas.ink)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            card.details,
                            style = type.mono,
                            color = atlas.ink,
                            modifier = Modifier.testTag(tag("tool_details")),
                        )
                        Text(
                            card.risk,
                            style = type.mono,
                            color = riskColor(card.risk, atlas),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag(tag("tool_risk")),
                        )
                        Text(
                            card.status,
                            style = type.bodySmall,
                            color = atlas.inkMuted,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag(tag("tool_status")),
                        )
                        if (card.result.isNotBlank()) {
                            Text(
                                card.result,
                                style = type.mono,
                                color = atlas.ink,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .testTag(tag("tool_result")),
                            )
                        }
                        if (card.pending) {
                            Row(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    onClick = { card.reject?.run() },
                                    modifier = Modifier.testTag(tag("tool_reject")),
                                    colors = StillwaterControls.dangerTextColors(),
                                ) { Text("Reject") }
                                Button(
                                    onClick = { card.approve?.run() },
                                    modifier = Modifier.testTag(tag("tool_approve")),
                                    shape = RoundedCornerShape(0.dp),
                                    colors = StillwaterControls.buttonColors(),
                                ) { Text("Approve") }
                            }
                        }
                    }
                }
                ChatTurnList(
                    turns = snapshot.turns,
                    packageName = context.packageName,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    onSuggest = { input = it },
                )
                attachment?.let { image ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
                            .padding(10.dp)
                            .testTag(tag("image_attachment")),
                    ) {
                        Text(
                            "Image attached",
                            style = type.label,
                            color = atlas.inkMuted,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        previewBitmap?.let { bitmap ->
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = "Attached image preview",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(144.dp)
                                    .padding(top = 6.dp)
                                    .testTag(tag("image_preview")),
                                contentScale = ContentScale.Fit,
                            )
                        } ?: Text(
                            "Loading image preview…",
                            style = type.bodySmall,
                            color = atlas.inkMuted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                        if (image.caption.isNotBlank()) {
                            Text(
                                image.caption,
                                style = type.bodySmall,
                                color = atlas.inkMuted,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .testTag(tag("image_size_caption")),
                            )
                        }
                        TextButton(
                            onClick = { image.onRemove.run() },
                            modifier = Modifier.testTag(tag("image_remove")),
                            colors = StillwaterControls.textButtonColors(),
                        ) { Text("Remove image") }
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(atlas.surface)
                        .border(width = 1.dp, color = atlas.border)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    ChatLiveStatus(idleMode, liveStatus, context.packageName)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("Write a message…", color = atlas.inkDisabled) },
                            minLines = 1,
                            maxLines = 4,
                            shape = RoundedCornerShape(0.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = atlas.ink,
                                unfocusedTextColor = atlas.ink,
                                disabledTextColor = atlas.inkDisabled,
                                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
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
                                .weight(1f)
                                .testTag(tag("chat_input")),
                        )
                        OutlinedButton(
                            onClick = { stop.run() },
                            enabled = controls.stop,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(tag("chat_stop")),
                            shape = RoundedCornerShape(0.dp),
                            colors = StillwaterControls.outlinedColors(),
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
                                .heightIn(min = 48.dp)
                                .testTag(tag("chat_send")),
                            shape = RoundedCornerShape(0.dp),
                            colors = StillwaterControls.buttonColors(),
                        ) { Text(stringResource(R.string.send)) }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { clear.run() },
                            enabled = controls.clear,
                            colors = StillwaterControls.textButtonColors(),
                        ) {
                            Text("Clear")
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
