package com.notcherry.lfm3pocket

/**
 * Builds the automator-facing flat transcript for [R.id.chat_transcript].
 *
 * Format matches historical [ChatSession] emit semantics so Stage 1 E2E can keep
 * grepping `YOU` / `LFM3` / `▌` after structured Atlas turns land (U05/U08).
 * See [docs/UI_E2E_CONTRACT.md].
 *
 * Reasoning-rail text is intentionally omitted from the flat string (v1).
 * Callers must pass cleaned answer bodies (U06); this helper does not strip tags.
 */
object FlatTranscriptFormatter {
    const val EMPTY_TRANSCRIPT: String = "No conversation yet.\n"
    const val USER_MARKER: String = "YOU"
    const val ASSISTANT_MARKER: String = "LFM3"
    const val STREAM_CURSOR: Char = '▌'

    enum class Role {
        USER,
        ASSISTANT,
    }

    /** Citation line pair under a completed assistant turn (optional). */
    data class Source(
        val title: String,
        val url: String,
    )

    /**
     * One chat turn for flat aggregation.
     *
     * @param role user or assistant
     * @param text body text (assistant text should already be cleaned)
     * @param streaming when true, append [STREAM_CURSOR] and omit the trailing blank line
     * @param sources numbered citations after completed assistant turns only
     */
    data class Turn(
        val role: Role,
        val text: String,
        val streaming: Boolean = false,
        val sources: List<Source> = emptyList(),
    )

    /** Formats [turns] into a single greppable transcript string. */
    @JvmStatic
    fun format(turns: List<Turn>): String {
        if (turns.isEmpty()) return EMPTY_TRANSCRIPT
        val out = StringBuilder()
        for (turn in turns) {
            when (turn.role) {
                Role.USER -> {
                    out.append(USER_MARKER).append('\n')
                        .append(turn.text).append("\n\n")
                }
                Role.ASSISTANT -> {
                    out.append(ASSISTANT_MARKER).append('\n').append(turn.text)
                    if (turn.streaming) {
                        out.append(STREAM_CURSOR)
                    } else {
                        out.append("\n\n")
                        appendSources(out, turn.sources)
                    }
                }
            }
        }
        return out.toString()
    }

    @JvmStatic
    fun format(vararg turns: Turn): String = format(turns.asList())

    /**
     * Adapts a [ChatSnapshot] into flat automator text.
     * Reasoning is omitted (v1). Empty / non-generating snapshots use [EMPTY_TRANSCRIPT].
     */
    @JvmStatic
    fun formatSnapshot(snapshot: ChatSnapshot?): String {
        if (snapshot == null || (snapshot.turns.isEmpty() && !snapshot.generating)) {
            return EMPTY_TRANSCRIPT
        }
        if (snapshot.turns.isEmpty()) return EMPTY_TRANSCRIPT
        val adapted = ArrayList<Turn>(snapshot.turns.size)
        for (turn in snapshot.turns) {
            val role = if (turn.role == "user") Role.USER else Role.ASSISTANT
            val sources = turn.sources.map { Source(it.title, it.url) }
            adapted.add(Turn(role, turn.text, turn.streaming, sources))
        }
        return format(adapted)
    }

    private fun appendSources(out: StringBuilder, sources: List<Source>) {
        for (i in sources.indices) {
            val source = sources[i]
            out.append('[').append(i + 1).append("] ")
                .append(source.title).append('\n')
                .append(source.url).append('\n')
        }
    }
}
