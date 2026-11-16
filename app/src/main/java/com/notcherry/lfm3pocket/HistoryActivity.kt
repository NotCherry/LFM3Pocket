package com.notcherry.lfm3pocket

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notcherry.lfm3pocket.ui.theme.PocketTheme
import com.notcherry.lfm3pocket.ui.theme.StillwaterControls
import com.notcherry.lfm3pocket.ui.theme.atlasColors
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
                    HistoryPreview(conversation.id, conversation.title,
                        last?.text?.replace('\n', ' ')?.take(120).orEmpty(), conversation.updatedAt)
                }
                main.post { conversations = rows; loading = false; error = cleanupError }
            } catch (failure: Exception) {
                main.post { loading = false; error = failure.message ?: "Could not read saved conversations" }
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
                main.post { error = failure.message ?: "Could not open this conversation" }
            }
        }
    }

    private fun deleteConversation(id: String) {
        worker.execute {
            dao.deleteConversation(id)
            val durableClean = HistoryMediaStore.deleteConversation(this, id)
            val mediaClean = HistoryMediaCleanup.deleteTemporaryAttachments(this) && durableClean
            main.post {
                if (selected?.conversation?.id == id) selected = null
                refreshConversations(if (mediaClean) null else
                    "Conversation deleted, but some temporary media could not be removed.")
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
                refreshConversations(if (mediaClean) null else
                    "History deleted, but some temporary media could not be removed.")
            }
        }
    }

    private fun startNewChat() {
        startActivity(android.content.Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_HISTORY_CONVERSATION_ID, UUID.randomUUID().toString())
            .putExtra(MainActivity.EXTRA_HISTORY_TITLE, "New conversation"))
        finish()
    }

    override fun onDestroy() {
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

@Composable
private fun HistoryScreen(
    packageName: String,
    conversations: List<HistoryPreview>,
    selected: HistoryTranscript?,
    loading: Boolean,
    error: String?,
    onOpen: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
    onNewChat: () -> Unit,
) {
    val tag = { name: String -> "$packageName:id/$name" }
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(atlas.surface)
            .padding(16.dp)
            .semantics { testTagsAsResourceId = true },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag(tag("history_back")),
                colors = StillwaterControls.textButtonColors(),
            ) {
                Text(if (selected == null) "Home" else "Back")
            }
            Button(
                onClick = onNewChat,
                modifier = Modifier.testTag(tag("history_new_chat")),
                shape = MaterialTheme.shapes.extraSmall,
                colors = StillwaterControls.buttonColors(),
            ) {
                Text("New chat")
            }
        }
        if (selected == null) {
            Text(
                "History",
                style = type.headline,
                color = atlas.ink,
                modifier = Modifier.testTag(tag("history_list_title")),
            )
            when {
                loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Text(error, color = atlas.danger, modifier = Modifier.testTag(tag("history_error")))
                conversations.isEmpty() -> Text(
                    "No saved conversations yet.",
                    color = atlas.inkMuted,
                    modifier = Modifier.padding(top = 20.dp).testTag(tag("history_empty")),
                )
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    item {
                        TextButton(
                            onClick = { confirmDeleteAll = true },
                            modifier = Modifier.testTag(tag("history_delete_all")),
                            colors = StillwaterControls.dangerTextColors(),
                        ) {
                            Text("Delete all history")
                        }
                    }
                    items(conversations, key = { it.id }) { row ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    TextButton(
                                        onClick = { onOpen(row.id) },
                                        modifier = Modifier.testTag(tag("history_item")),
                                    ) {
                                        Text(row.title, style = type.title, color = atlas.ink)
                                    }
                                    Text(
                                        row.preview.ifBlank { "No message preview" },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = type.bodySmall,
                                        color = atlas.inkMuted,
                                        modifier = Modifier
                                            .padding(start = 12.dp, top = 2.dp)
                                            .testTag(tag("history_item_preview")),
                                    )
                                    Text(
                                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                            .format(Date(row.updatedAt)),
                                        style = type.bodySmall,
                                        color = atlas.inkMuted,
                                        modifier = Modifier
                                            .padding(start = 12.dp, top = 4.dp)
                                            .testTag(tag("history_item_timestamp")),
                                    )
                                }
                                TextButton(
                                    onClick = { deleteTarget = row.id to row.title },
                                    modifier = Modifier.testTag(tag("history_delete_${row.id}")),
                                    colors = StillwaterControls.dangerTextColors(),
                                ) {
                                    Text("Delete")
                                }
                            }
                            HorizontalDivider(color = atlas.border)
                        }
                    }
                }
            }
            Text(
                "Saved on this phone. Excluded from Android backup. Audio and photos are temporary and removed after processing.",
                style = type.bodySmall,
                color = atlas.inkMuted,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).testTag(tag("history_retention_notice")),
            )
        } else {
            Text(
                selected.conversation.title,
                style = type.headline,
                color = atlas.ink,
                modifier = Modifier.testTag(tag("history_reader_title")).semantics {
                    contentDescription = selected.conversation.title
                },
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
                                    .padding(vertical = 8.dp)
                                    .background(atlas.surfaceRaised, MaterialTheme.shapes.medium)
                                    .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
                                    .padding(12.dp),
                            ) {
                                Text("Android action", style = type.label, color = atlas.inkMuted)
                                Text(
                                    message.text,
                                    style = type.mono,
                                    color = atlas.ink,
                                    modifier = Modifier
                                        .padding(top = 4.dp)
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
                                cleaned?.reasoning,
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
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                    if (!message.speechTranscript.isNullOrBlank()) {
                        Text(
                            "Speech transcript: ${message.speechTranscript}",
                            style = type.bodySmall,
                            color = atlas.inkMuted,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag(tag("history_message_speech"))
                                .semantics { contentDescription = message.speechTranscript },
                        )
                    } else if (message.speechTranscriptStatus == "UNAVAILABLE") {
                        Text(
                            "Speech transcript unavailable",
                            style = type.bodySmall,
                            color = atlas.inkMuted,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag(tag("history_message_speech_unavailable"))
                                .semantics { contentDescription = "Speech transcript unavailable" },
                        )
                    }
                    if (message.role == "assistant" && message.turnStatus != "COMPLETED") {
                        Text(
                            "Turn ${message.turnStatus.lowercase()}",
                            style = type.bodySmall,
                            color = atlas.inkMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    selected.outcomes.filter { it.messageId == message.id }.forEach { outcome ->
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = atlas.border)
                        Text(
                            "${outcome.tool} · ${outcome.status}",
                            style = type.label,
                            color = atlas.inkMuted,
                        )
                        Text(outcome.argumentsJson, style = type.mono, color = atlas.inkMuted)
                        Text(
                            outcome.resultText,
                            style = type.body,
                            color = atlas.ink,
                            modifier = Modifier.testTag(tag("history_tool_outcome_result")).semantics {
                                contentDescription = outcome.resultText
                            },
                        )
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
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            containerColor = atlas.surfaceOverlay,
            titleContentColor = atlas.ink,
            textContentColor = atlas.inkMuted,
            title = { Text("Delete conversation?", color = atlas.ink) },
            text = { Text("Delete ‘${target.second}’ and all of its saved messages and action results?", color = atlas.inkMuted) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteConversation(target.first)
                        deleteTarget = null
                    },
                    modifier = Modifier.testTag(tag("history_confirm_delete")),
                    colors = StillwaterControls.dangerTextColors(),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    modifier = Modifier.testTag(tag("history_cancel_delete")),
                    colors = StillwaterControls.textButtonColors(),
                ) { Text("Cancel") }
            },
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            containerColor = atlas.surfaceOverlay,
            titleContentColor = atlas.ink,
            textContentColor = atlas.inkMuted,
            title = { Text("Delete all history?", color = atlas.ink) },
            text = { Text("This permanently deletes every saved conversation on this device.", color = atlas.inkMuted) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAll()
                        confirmDeleteAll = false
                    },
                    modifier = Modifier.testTag(tag("history_confirm_delete_all")),
                    colors = StillwaterControls.dangerTextColors(),
                ) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDeleteAll = false },
                    modifier = Modifier.testTag(tag("history_cancel_delete_all")),
                    colors = StillwaterControls.textButtonColors(),
                ) { Text("Cancel") }
            },
        )
    }
}
