package com.notcherry.lfm3pocket

import androidx.room.RoomDatabase
import java.util.UUID
import java.util.concurrent.Callable

/** Owns atomic, idempotent writes for chat turns. Call from a worker thread. */
class HistoryRepository(private val database: HistoryDatabase) {
    enum class Status(val storedValue: String) {
        COMPLETED("COMPLETED"),
        CANCELLED("CANCELLED"),
        FAILED("FAILED"),
        GENERATING("GENERATING"),
        INTERRUPTED("INTERRUPTED"),
    }

    data class ToolExecution(
        val requestId: String,
        val tool: String,
        val argumentsJson: String,
        val succeeded: Boolean,
        val status: String,
        val resultText: String,
        val completedAt: Long,
    )

    data class TurnStart @JvmOverloads constructor(
        val id: String,
        val conversationId: String,
        val title: String,
        val userText: String,
        val startedAt: Long,
        val mediaRef: String? = null,
    ) {
        init {
            require(id.isNotBlank() && conversationId.isNotBlank() && title.isNotBlank())
            require(startedAt >= 0)
        }
    }

    data class Turn @JvmOverloads constructor(
        val id: String,
        val conversationId: String,
        val title: String,
        val userText: String,
        val assistantText: String,
        val speechTranscript: String?,
        val status: Status,
        val completedAt: Long,
        val toolExecution: ToolExecution? = null,
        val speechTranscriptStatus: String? = null,
        val mediaRef: String? = null,
        val inferenceStats: String? = null,
    ) {
        init {
            require(id.isNotBlank() && conversationId.isNotBlank() && title.isNotBlank())
            require(completedAt >= 0)
            require(status != Status.COMPLETED || assistantText.isNotBlank())
            require(status != Status.GENERATING)
            require(speechTranscriptStatus == null || speechTranscriptStatus in setOf("AVAILABLE", "UNAVAILABLE"))
            require(speechTranscriptStatus != "AVAILABLE" || !speechTranscript.isNullOrBlank())
            require(speechTranscriptStatus != "UNAVAILABLE" || speechTranscript.isNullOrBlank())
        }
    }

    data class StoredMessage(
        val role: String,
        val text: String,
        val turnId: String?,
        val turnStatus: String,
        val mediaRef: String?,
        val speechTranscript: String?,
        val speechTranscriptStatus: String?,
        val inferenceStats: String?,
    )

    companion object {
        const val INTERRUPTED_PLACEHOLDER = "Interrupted — tap Retry to send again."
    }

    /** Persists the user turn and an empty GENERATING assistant row before inference. */
    fun beginTurn(start: TurnStart): Boolean = database.runInTransaction(Callable {
        val dao = database.history()
        if (dao.messageForTurn(start.id) != null) return@Callable false
        ensureConversation(dao, start.conversationId, start.title, start.startedAt)
        val firstSequence = (dao.lastSequence(start.conversationId) ?: -1L) + 1L
        dao.insertMessage(HistoryMessage(
            UUID.randomUUID().toString(), start.conversationId, firstSequence, "user", start.userText,
            null, start.startedAt, null, Status.COMPLETED.storedValue, null, start.mediaRef,
        ))
        dao.insertMessage(HistoryMessage(
            UUID.randomUUID().toString(), start.conversationId, firstSequence + 1L, "assistant", "",
            null, start.startedAt, start.id, Status.GENERATING.storedValue, null, null,
        ))
        true
    })

    /** Streams a partial assistant reply into storage. No-op when the turn is already terminal. */
    fun updateTurnPartial(turnId: String, assistantText: String, at: Long): Boolean =
        database.runInTransaction(Callable {
            val dao = database.history()
            val existing = dao.messageForTurn(turnId) ?: return@Callable false
            if (existing.turnStatus != Status.GENERATING.storedValue) return@Callable false
            dao.updateTurnText(turnId, assistantText)
            dao.touchConversation(existing.conversationId, at)
            true
        })

    /**
     * Completes a turn. When [beginTurn] already wrote rows, updates them in place.
     * Otherwise inserts both messages atomically (legacy / test path).
     */
    fun completeTurn(turn: Turn): Boolean = database.runInTransaction(Callable {
        val dao = database.history()
        val existing = dao.messageForTurn(turn.id)
        if (existing != null) {
            if (existing.turnStatus != Status.GENERATING.storedValue
                && existing.turnStatus != Status.INTERRUPTED.storedValue) {
                return@Callable false
            }
            ensureConversation(dao, turn.conversationId, turn.title, turn.completedAt)
            dao.updateTurn(turn.id, turn.assistantText, turn.status.storedValue, turn.inferenceStats)
            val user = dao.messageAt(existing.conversationId, existing.sequence - 1L)
            if (user != null && user.role == "user") {
                dao.updateSpeech(user.id, turn.speechTranscript, turn.speechTranscriptStatus)
            }
            turn.toolExecution?.let { execution ->
                if (dao.toolOutcome(execution.requestId) == null) {
                    dao.insertToolOutcome(HistoryToolOutcome(
                        UUID.randomUUID().toString(), existing.id, execution.requestId, execution.tool,
                        execution.argumentsJson, execution.succeeded, execution.status, execution.resultText,
                        execution.completedAt,
                    ))
                }
            }
            dao.touchConversation(turn.conversationId, turn.completedAt)
            return@Callable true
        }

        ensureConversation(dao, turn.conversationId, turn.title, turn.completedAt)
        val firstSequence = (dao.lastSequence(turn.conversationId) ?: -1L) + 1L
        val userId = UUID.randomUUID().toString()
        val assistantId = UUID.randomUUID().toString()
        dao.insertMessage(HistoryMessage(
            userId, turn.conversationId, firstSequence, "user", turn.userText,
            turn.speechTranscript, turn.completedAt, null, Status.COMPLETED.storedValue,
            turn.speechTranscriptStatus, turn.mediaRef,
        ))
        dao.insertMessage(HistoryMessage(
            assistantId, turn.conversationId, firstSequence + 1L, "assistant", turn.assistantText,
            null, turn.completedAt, turn.id, turn.status.storedValue, null, null, turn.inferenceStats,
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

    /** Marks in-flight GENERATING assistant rows as INTERRUPTED after process death. */
    fun markInterruptedGenerations(at: Long): Int = database.runInTransaction(Callable {
        val dao = database.history()
        val changed = dao.markGeneratingInterrupted(INTERRUPTED_PLACEHOLDER)
        if (changed > 0) {
            for (conversation in dao.conversations()) {
                dao.touchConversation(conversation.id, at)
            }
        }
        changed
    })

    fun messages(conversationId: String): List<StoredMessage> =
        database.history().messages(conversationId).map {
            StoredMessage(
                it.role, it.text, it.turnId, it.turnStatus, it.mediaRef,
                it.speechTranscript, it.speechTranscriptStatus, it.inferenceStats,
            )
        }

    /** Records an action outcome after consent/execution, attaching it to the latest reply when available. */
    fun appendToolOutcome(conversationId: String, title: String, execution: ToolExecution): Boolean =
        database.runInTransaction(Callable {
            val dao = database.history()
            if (dao.toolOutcome(execution.requestId) != null) return@Callable false
            ensureConversation(dao, conversationId, title, execution.completedAt)
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

    private fun ensureConversation(dao: HistoryDao, id: String, title: String, at: Long) {
        if (dao.conversation(id) == null) {
            dao.insertConversation(HistoryConversation(id, title, at, at))
        } else {
            dao.touchConversation(id, at)
        }
    }
}
