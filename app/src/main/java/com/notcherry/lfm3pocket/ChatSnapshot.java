package com.notcherry.lfm3pocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable structured chat snapshot for Atlas UI (U05).
 * Flat automator text is derived via {@link FlatTranscript} → {@link FlatTranscriptFormatter}.
 */
public final class ChatSnapshot {
    public static final class Source {
        public final String title;
        public final String url;
        public final String snippet;

        public Source(String title, String url, String snippet) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.snippet = snippet == null ? "" : snippet;
        }
    }

    /**
     * One visible turn. {@code role} is {@code "user"} or {@code "assistant"}.
     * {@code reasoning} is optional cleaned think-block text for the Reasoning rail (never in answer body).
     */
    public static final class Turn {
        public final String role;
        public final String text;
        public final String reasoning;
        public final List<Source> sources;
        public final boolean streaming;

        public Turn(String role, String text, String reasoning, List<Source> sources, boolean streaming) {
            this.role = Objects.requireNonNull(role, "role");
            this.text = text == null ? "" : text;
            this.reasoning = reasoning == null || reasoning.isEmpty() ? null : reasoning;
            List<Source> copy = sources == null ? Collections.emptyList() : new ArrayList<>(sources);
            this.sources = Collections.unmodifiableList(copy);
            this.streaming = streaming;
        }
    }

    public final List<Turn> turns;
    public final String status;
    public final boolean generating;

    public ChatSnapshot(List<Turn> turns, String status, boolean generating) {
        List<Turn> copy = turns == null ? Collections.emptyList() : new ArrayList<>(turns);
        this.turns = Collections.unmodifiableList(copy);
        this.status = status == null ? "" : status;
        this.generating = generating;
    }
}
