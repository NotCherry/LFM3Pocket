package com.notcherry.lfm3pocket

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.notcherry.lfm3pocket.ui.theme.PocketTheme
import com.notcherry.lfm3pocket.ui.theme.StillwaterControls
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasSpacing
import com.notcherry.lfm3pocket.ui.theme.atlasTypography
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

private data class HistoryPreview(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAt: Long,
)

private data class HistoryTranscript(
    val conversation: HistoryConversation,
    val messages: List<HistoryMessage>,
    val outcomes: List<HistoryToolOutcome>,
)

private val MARKDOWN_NOISE = Regex("""(`{1,3}|#{1,6}\s*|\*{1,2}|_{1,2})""")
private val WHITESPACE_RUN = Regex("""\s+""")

internal fun cleanHistoryPreviewText(role: String, raw: String): String {
    val body = if (role == "assistant") {
        ThinkingSanitizer.clean(raw).answer
    } else {
        raw
    }
    return body
        .replace(MARKDOWN_NOISE, "")
        .replace(WHITESPACE_RUN, " ")
        .trim()
        .take(140)
}

class HistoryActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dao: HistoryDao
    private var conversations by mutableStateOf<List<HistoryPreview>>(emptyList())
    private var selected by mutableStateOf<HistoryTranscript?>(null)
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)

    private var attachedInterfaceScale = InterfaceSize.DEFAULT

    override fun attachBaseContext(newBase: Context) {
        attachedInterfaceScale = InterfaceSize.read(newBase)
        super.attachBaseContext(InterfaceSize.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        InterfaceSize.recreateIfChanged(this, attachedInterfaceScale)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AndroidPermissionGate.usesLegacySystemBarColorApis(android.os.Build.VERSION.SDK_INT)) {
            window.statusBarColor = StillwaterUi.SURFACE
            window.navigationBarColor = StillwaterUi.SURFACE
        }
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        dao = HistoryDatabase.get(this).history()
        setContent {
            PocketTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HistoryScreen(
                        packageName = packageName,
                        conversations = conversations,
                        selected = selected,
                        loading = loading,
                        error = error,
                        onOpen = ::openConversation,
                        onContinueChat = ::continueConversationInChat,
                        onDeleteConversation = ::deleteConversation,
                        onDeleteAll = ::deleteAllConversations,
                        onBack = { if (selected != null) selected = null else finish() },
                        onNewChat = ::startNewChat,
                    )
                }
            }
        }
        refreshConversations()
    }

    private fun refreshConversations(cleanupError: String? = null) {
        loading = true
        worker.execute {
            try {
                val rows = dao.conversations().map { conversation ->
                    val last = dao.messages(conversation.id).lastOrNull()
                    val snippet = if (last != null) cleanHistoryPreviewText(last.role, last.text) else ""
                    HistoryPreview(conversation.id, conversation.title, snippet, conversation.updatedAt)
                }
                main.post { conversations = rows; loading = false; error = cleanupError }
            } catch (failure: Exception) {
                main.post {
                    loading = false
                    error = failure.message ?: getString(R.string.history_read_error)
                }
            }
        }
    }

    private fun openConversation(id: String) {
        worker.execute {
            try {
                val conversation = dao.conversation(id) ?: return@execute
                val transcript = HistoryTranscript(conversation, dao.messages(id), dao.toolOutcomes(id))
                main.post { selected = transcript; error = null }
            } catch (failure: Exception) {
                main.post {
                    error = failure.message ?: getString(R.string.history_open_error)
                }
            }
        }
    }

    private fun continueConversationInChat(conversation: HistoryConversation) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_HISTORY_CONVERSATION_ID, conversation.id)
                .putExtra(MainActivity.EXTRA_HISTORY_TITLE, conversation.title),
        )
        finish()
    }

    private fun deleteConversation(id: String) {
        worker.execute {
            dao.deleteConversation(id)
            val durableClean = HistoryMediaStore.deleteConversation(this, id)
            val mediaClean = HistoryMediaCleanup.deleteTemporaryAttachments(this) && durableClean
            main.post {
                if (selected?.conversation?.id == id) selected = null
                refreshConversations(
                    if (mediaClean) null
                    else "Conversation deleted, but some temporary media could not be removed.",
                )
            }
        }
    }

    private fun deleteAllConversations() {
        worker.execute {
            dao.deleteAllConversations()
            val durableClean = HistoryMediaStore.deleteAll(this)
            val mediaClean = HistoryMediaCleanup.deleteTemporaryAttachments(this) && durableClean
            main.post {
                selected = null
                refreshConversations(
                    if (mediaClean) null
                    else "History deleted, but some temporary media could not be removed.",
                )
            }
        }
    }

    private fun startNewChat() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_HISTORY_CONVERSATION_ID, UUID.randomUUID().toString())
                .putExtra(MainActivity.EXTRA_HISTORY_TITLE, getString(R.string.new_conversation_title)),
        )
        finish()
    }

    override fun onDestroy() {
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryScreen(
    packageName: String,
    conversations: List<HistoryPreview>,
    selected: HistoryTranscript?,
    loading: Boolean,
    error: String?,
    onOpen: (String) -> Unit,
    onContinueChat: (HistoryConversation) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
    onNewChat: () -> Unit,
) {
    val tag = { name: String -> "$packageName:id/$name" }
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    val space = MaterialTheme.atlasSpacing
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(atlas.surface)
            .systemBarsPadding()
            .padding(horizontal = space.screen, vertical = space.space2)
            .semantics { testTagsAsResourceId = true },
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.Center,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag(tag("history_back")),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = space.space1),
                colors = StillwaterControls.textButtonColors(),
            ) {
                Text(
                    if (selected == null) stringResource(R.string.history_home) else stringResource(R.string.history_back),
                    style = type.body,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space.space2),
            ) {
                if (selected != null) {
                    OutlinedButton(
                        onClick = { onContinueChat(selected.conversation) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .heightIn(min = 40.dp)
                            .testTag(tag("history_continue_chat")),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, atlas.borderStrong),
                        colors = StillwaterControls.outlinedColors(),
                    ) {
                        Text(stringResource(R.string.history_continue_chat))
                    }
                }
                Button(
                    onClick = onNewChat,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .heightIn(min = 40.dp)
                        .testTag(tag("history_new_chat")),
                    shape = MaterialTheme.shapes.medium,
                    colors = StillwaterControls.buttonColors(),
                ) {
                    Text(stringResource(R.string.new_chat))
                }
            }
        }
        Spacer(Modifier.height(space.space1))
        HorizontalDivider(color = atlas.border, thickness = 1.dp)
        Spacer(Modifier.height(space.space3))

        if (selected == null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.history),
                    style = type.headline,
                    color = atlas.ink,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(tag("history_list_title")),
                )
                if (!loading && conversations.isNotEmpty()) {
                    TextButton(
                        onClick = { confirmDeleteAll = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag(tag("history_delete_all")),
                        shape = MaterialTheme.shapes.medium,
                        colors = StillwaterControls.dangerTextColors(),
                    ) {
                        Text(stringResource(R.string.history_delete_all), style = type.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(space.space2))
            when {
                loading -> Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = atlas.accent)
                }
                error != null -> Text(
                    error,
                    color = atlas.danger,
                    style = type.body,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = space.space3)
                        .testTag(tag("history_error")),
                )
                conversations.isEmpty() -> Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = space.space5),
                ) {
                    Text(
                        stringResource(R.string.history_empty_title),
                        style = type.title,
                        color = atlas.ink,
                        modifier = Modifier.testTag(tag("history_empty")),
                    )
                    Spacer(Modifier.height(space.space2))
                    Text(
                        stringResource(R.string.history_empty_subtitle),
                        style = type.body,
                        color = atlas.inkSoft,
                    )
                }
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(conversations, key = { it.id }) { row ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = space.space3),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable { onOpen(row.id) }
                                        .padding(end = space.space2)
                                        .testTag(tag("history_item")),
                                ) {
                                    Text(
                                        row.title,
                                        style = type.title,
                                        color = atlas.ink,
                                    )
                                    Text(
                                        row.preview.ifBlank { stringResource(R.string.history_no_preview) },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = type.bodySmall,
                                        color = atlas.inkSoft,
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .testTag(tag("history_item_preview")),
                                    )
                                    Text(
                                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                            .format(Date(row.updatedAt)),
                                        style = type.mono.copy(fontSize = type.bodySmall.fontSize),
                                        color = atlas.inkMuted,
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .testTag(tag("history_item_timestamp")),
                                    )
                                }
                                TextButton(
                                    onClick = { deleteTarget = row.id to row.title },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag(tag("history_delete_${row.id}")),
                                    shape = MaterialTheme.shapes.medium,
                                    contentPadding = PaddingValues(horizontal = space.space2, vertical = space.space1),
                                    colors = StillwaterControls.dangerTextColors(),
                                ) {
                                    Text(stringResource(R.string.history_delete), style = type.bodySmall)
                                }
                            }
                            HorizontalDivider(color = atlas.border, thickness = 1.dp)
                        }
                    }
                }
            }
            HorizontalDivider(color = atlas.border, thickness = 1.dp)
            Text(
                stringResource(R.string.history_retention_notice),
                style = type.bodySmall,
                color = atlas.inkMuted,
                modifier = Modifier
                    .padding(top = space.space2, bottom = space.space1)
                    .testTag(tag("history_retention_notice")),
            )
        } else {
            Text(
                selected.conversation.title,
                style = type.headline,
                color = atlas.ink,
                modifier = Modifier
                    .padding(bottom = space.space2)
                    .testTag(tag("history_reader_title"))
                    .semantics { contentDescription = selected.conversation.title },
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag(tag("history_reader")),
            ) {
                selected.messages.forEach { message ->
                    when (message.role) {
                        "tool" -> {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = space.space2)
                                    .background(atlas.surfaceRaised, MaterialTheme.shapes.medium)
                                    .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
                                    .padding(space.space4),
                            ) {
                                Text(
                                    stringResource(R.string.android_action),
                                    style = type.label,
                                    color = atlas.inkMuted,
                                )
                                Text(
                                    message.text,
                                    style = type.mono,
                                    color = atlas.ink,
                                    modifier = Modifier
                                        .padding(top = space.space1)
                                        .testTag(tag("history_message_tool"))
                                        .semantics { contentDescription = message.text },
                                )
                            }
                        }
                        else -> {
                            val cleaned = if (message.role == "assistant") {
                                ThinkingSanitizer.clean(message.text)
                            } else {
                                null
                            }
                            val turn = ChatSnapshot.Turn(
                                message.role,
                                cleaned?.answer ?: message.text,
                                message.reasoning?.takeIf { it.isNotBlank() } ?: cleaned?.reasoning,
                                emptyList(),
                                false,
                            )
                            ChatTurn(
                                turn = turn,
                                packageName = packageName,
                                userMessageTag = if (message.role == "user") "history_message_user" else null,
                                assistantMessageTag = if (message.role == "assistant") "history_message_assistant" else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = space.space2),
                            )
                        }
                    }
                    if (!message.speechTranscript.isNullOrBlank()) {
                        Text(
                            stringResource(R.string.history_speech_transcript, message.speechTranscript),
                            style = type.bodySmall,
                            color = atlas.inkMuted,
                            modifier = Modifier
                                .padding(top = space.space1)
                                .testTag(tag("history_message_speech"))
                                .semantics { contentDescription = message.speechTranscript },
                        )
                    } else if (message.speechTranscriptStatus == "UNAVAILABLE") {
                        val unavailText = stringResource(R.string.history_speech_unavailable)
                        Text(
                            unavailText,
                            style = type.bodySmall,
                            color = atlas.inkMuted,
                            modifier = Modifier
                                .padding(top = space.space1)
                                .testTag(tag("history_message_speech_unavailable"))
                                .semantics { contentDescription = unavailText },
                        )
                    }
                    if (message.role == "assistant" && message.turnStatus != "COMPLETED") {
                        Text(
                            stringResource(R.string.history_turn_status, message.turnStatus.lowercase()),
                            style = type.bodySmall,
                            color = atlas.inkMuted,
                            modifier = Modifier.padding(top = space.space1),
                        )
                    }
                    selected.outcomes.filter { it.messageId == message.id }.forEach { outcome ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = space.space2)
                                .background(atlas.surfaceRaised, MaterialTheme.shapes.medium)
                                .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
                                .padding(space.space3),
                        ) {
                            Text(
                                "${outcome.tool} · ${outcome.status}",
                                style = type.label,
                                color = if (outcome.succeeded) atlas.ok else atlas.warn,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(outcome.argumentsJson, style = type.mono, color = atlas.inkMuted)
                            Spacer(Modifier.height(space.space1))
                            Text(
                                outcome.resultText,
                                style = type.body,
                                color = atlas.ink,
                                modifier = Modifier
                                    .testTag(tag("history_tool_outcome_result"))
                                    .semantics { contentDescription = outcome.resultText },
                            )
                        }
                    }
                }
            }
        }
        if (error != null && selected != null) {
            Text(error, color = atlas.danger, modifier = Modifier.testTag(tag("history_error")))
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            modifier = Modifier
                .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
                .semantics { testTagsAsResourceId = true },
            shape = MaterialTheme.shapes.medium,
            containerColor = atlas.surfaceOverlay,
            titleContentColor = atlas.ink,
            textContentColor = atlas.inkSoft,
            title = { Text(stringResource(R.string.history_delete_one_title), style = type.headline, color = atlas.ink) },
            text = {
                Text(
                    stringResource(R.string.history_delete_one_body, target.second),
                    style = type.body,
                    color = atlas.inkSoft,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteConversation(target.first)
                        deleteTarget = null
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.testTag(tag("history_confirm_delete")),
                    colors = StillwaterControls.dangerTextColors(),
                ) { Text(stringResource(R.string.history_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.testTag(tag("history_cancel_delete")),
                    colors = StillwaterControls.textButtonColors(),
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            modifier = Modifier
                .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
                .semantics { testTagsAsResourceId = true },
            shape = MaterialTheme.shapes.medium,
            containerColor = atlas.surfaceOverlay,
            titleContentColor = atlas.ink,
            textContentColor = atlas.inkSoft,
            title = { Text(stringResource(R.string.history_delete_all_title), style = type.headline, color = atlas.ink) },
            text = {
                Text(
                    stringResource(R.string.history_delete_all_body),
                    style = type.body,
                    color = atlas.inkSoft,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAll()
                        confirmDeleteAll = false
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.testTag(tag("history_confirm_delete_all")),
                    colors = StillwaterControls.dangerTextColors(),
                ) { Text(stringResource(R.string.history_delete_all_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDeleteAll = false },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.testTag(tag("history_cancel_delete_all")),
                    colors = StillwaterControls.textButtonColors(),
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
