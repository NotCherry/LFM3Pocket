package com.notcherry.lfm3pocket

import androidx.room.RoomDatabase
import java.util.UUID
import java.util.concurrent.Callable

/** Owns atomic, idempotent writes for a completed chat turn. Call from a worker thread. */
class HistoryRepository(private val database: HistoryDatabase) {
    enum class Status(val storedValue: String) { COMPLETED("COMPLETED"), CANCELLED("CANCELLED"), FAILED("FAILED") }

    data class ToolExecution(
        val requestId: String,
        val tool: String,
        val argumentsJson: String,
        val succeeded: Boolean,
        val status: String,
        val resultText: String,
        val completedAt: Long,
    )

    data class Turn(
        val id: String,
        val conversationId: String,
        val title: String,
        val userText: String,
        val assistantText: String,
        val speechTranscript: String?,
        val status: Status,
        val completedAt: Long,
        val toolExecution: ToolExecution? = null,
    ) {
        init {
            require(id.isNotBlank() && conversationId.isNotBlank() && title.isNotBlank())
            require(completedAt >= 0)
            require(status != Status.COMPLETED || assistantText.isNotBlank())
        }
    }

    /** Returns false for a turn already committed; failures roll back every row. */
    fun completeTurn(turn: Turn): Boolean = database.runInTransaction(Callable {
        val dao = database.history()
        if (dao.messageForTurn(turn.id) != null) return@Callable false

        if (dao.conversation(turn.conversationId) == null) {
            dao.insertConversation(HistoryConversation(turn.conversationId, turn.title, turn.completedAt, turn.completedAt))
        } else {
            dao.touchConversation(turn.conversationId, turn.completedAt)
        }

        val firstSequence = (dao.lastSequence(turn.conversationId) ?: -1L) + 1L
        val userId = UUID.randomUUID().toString()
        val assistantId = UUID.randomUUID().toString()
        dao.insertMessage(HistoryMessage(
            userId, turn.conversationId, firstSequence, "user", turn.userText,
            turn.speechTranscript, turn.completedAt,
        ))
        dao.insertMessage(HistoryMessage(
            assistantId, turn.conversationId, firstSequence + 1L, "assistant", turn.assistantText,
            null, turn.completedAt, turn.id, turn.status.storedValue,
        ))
        turn.toolExecution?.let { execution ->
            dao.insertToolOutcome(HistoryToolOutcome(
                UUID.randomUUID().toString(), assistantId, execution.requestId, execution.tool,
                execution.argumentsJson, execution.succeeded, execution.status, execution.resultText,
                execution.completedAt,
            ))
        }
        true
    })

    /** Records an action outcome after consent/execution, attaching it to the latest reply when available. */
    fun appendToolOutcome(conversationId: String, title: String, execution: ToolExecution): Boolean =
        database.runInTransaction(Callable {
            val dao = database.history()
            if (dao.toolOutcome(execution.requestId) != null) return@Callable false
            val conversation = dao.conversation(conversationId)
            if (conversation == null) {
                dao.insertConversation(HistoryConversation(conversationId, title, execution.completedAt, execution.completedAt))
            } else {
                dao.touchConversation(conversationId, execution.completedAt)
            }
            val target = dao.latestAssistant(conversationId) ?: run {
                val sequence = (dao.lastSequence(conversationId) ?: -1L) + 1L
                val id = UUID.randomUUID().toString()
                dao.insertMessage(HistoryMessage(id, conversationId, sequence, "tool", execution.resultText,
                    null, execution.completedAt))
                dao.messages(conversationId).last()
            }
            dao.insertToolOutcome(HistoryToolOutcome(
                UUID.randomUUID().toString(), target.id, execution.requestId, execution.tool,
                execution.argumentsJson, execution.succeeded, execution.status, execution.resultText,
                execution.completedAt,
            ))
            true
        })
}
