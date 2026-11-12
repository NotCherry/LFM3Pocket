package com.notcherry.lfm3pocket

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dao = HistoryDatabase.get(this).history()
        setContent {
            MaterialTheme {
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
            val mediaClean = HistoryMediaCleanup.deleteTemporaryAttachments(this)
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
            val mediaClean = HistoryMediaCleanup.deleteTemporaryAttachments(this)
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
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp).semantics { testTagsAsResourceId = true }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(tag("history_back"))) {
                Text(if (selected == null) "Home" else "Back")
            }
            Button(onClick = onNewChat, modifier = Modifier.testTag(tag("history_new_chat"))) {
                Text("New chat")
            }
        }
        Text("History stays on this device and is excluded from Android backup. Conversation text and any saved speech transcript are retained; audio and image attachments are temporary and removed after processing.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp).testTag(tag("history_retention_notice")))
        if (selected == null) {
            Text("Saved conversations", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(tag("history_list_title")))
            when {
                loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Text(error, modifier = Modifier.testTag(tag("history_error")))
                conversations.isEmpty() -> Text("No saved conversations yet.",
                    modifier = Modifier.padding(top = 20.dp).testTag(tag("history_empty")))
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    item {
                        TextButton(onClick = { confirmDeleteAll = true },
                            modifier = Modifier.testTag(tag("history_delete_all"))) {
                            Text("Delete all history")
                        }
                    }
                    items(conversations, key = { it.id }) { row ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Row(Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    TextButton(onClick = { onOpen(row.id) },
                                        modifier = Modifier.testTag(tag("history_item"))) {
                                        Text(row.title, style = MaterialTheme.typography.titleMedium)
                                    }
                                    Text(row.preview.ifBlank { "No message preview" }, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                                            .testTag(tag("history_item_preview")))
                                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                        .format(Date(row.updatedAt)), style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                                            .testTag(tag("history_item_timestamp")))
                                }
                                TextButton(onClick = { deleteTarget = row.id to row.title },
                                    modifier = Modifier.testTag(tag("history_delete_${row.id}"))) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Text(selected.conversation.title, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(tag("history_reader_title")).semantics {
                    contentDescription = selected.conversation.title
                })
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(tag("history_reader"))) {
                items(selected.messages, key = { it.id }) { message ->
                    val role = when (message.role) {
                        "user" -> "You"
                        "assistant" -> "LFM3"
                        "tool" -> "Android action"
                        else -> message.role.replaceFirstChar { it.uppercase() }
                    }
                    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(role, style = MaterialTheme.typography.labelLarge)
                            SelectionContainer {
                                Text(message.text, modifier = Modifier
                                    .padding(top = 4.dp)
                                    .testTag(tag(if (message.role == "user") "history_message_user"
                                        else if (message.role == "assistant") "history_message_assistant" else "history_message_tool"))
                                    .semantics { contentDescription = message.text })
                            }
                            if (!message.speechTranscript.isNullOrBlank()) {
                                Text("Speech transcript: ${message.speechTranscript}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp)
                                        .testTag(tag("history_message_speech"))
                                        .semantics { contentDescription = message.speechTranscript })
                            } else if (message.speechTranscriptStatus == "UNAVAILABLE") {
                                Text("Speech transcript unavailable",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp)
                                        .testTag(tag("history_message_speech_unavailable"))
                                        .semantics { contentDescription = "Speech transcript unavailable" })
                            }
                            if (message.role == "assistant" && message.turnStatus != "COMPLETED") {
                                Text("Turn ${message.turnStatus.lowercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                            selected.outcomes.filter { it.messageId == message.id }.forEach { outcome ->
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                Text("${outcome.tool} · ${outcome.status}",
                                    style = MaterialTheme.typography.labelMedium)
                                Text(outcome.argumentsJson, style = MaterialTheme.typography.bodySmall)
                                Text(outcome.resultText, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag(tag("history_tool_outcome_result")).semantics {
                                        contentDescription = outcome.resultText
                                    })
                            }
                        }
                    }
                }
            }
        }
        if (error != null && selected != null) {
            Text(error, modifier = Modifier.testTag(tag("history_error")))
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { deleteTarget = null },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text("Delete conversation?") },
            text = { Text("Delete ‘${target.second}’ and all of its saved messages and action results?") },
            confirmButton = { TextButton(onClick = {
                onDeleteConversation(target.first); deleteTarget = null
            }, modifier = Modifier.testTag(tag("history_confirm_delete"))) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null },
                modifier = Modifier.testTag(tag("history_cancel_delete"))) { Text("Cancel") } })
    }
    if (confirmDeleteAll) {
        AlertDialog(onDismissRequest = { confirmDeleteAll = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text("Delete all history?") },
            text = { Text("This permanently deletes every saved conversation on this device.") },
            confirmButton = { TextButton(onClick = {
                onDeleteAll(); confirmDeleteAll = false
            }, modifier = Modifier.testTag(tag("history_confirm_delete_all"))) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false },
                modifier = Modifier.testTag(tag("history_cancel_delete_all"))) { Text("Cancel") } })
    }
}
