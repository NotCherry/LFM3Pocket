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

    /** Clickable attachment metadata carried from ChatSession into ChatTurnList / ChatPanel. */
    public static final class AttachmentRef {
        public final String kind;
        public final String label;
        public final String path;
        public final String sourceUri;
        public final String fallbackText;

        public AttachmentRef(String kind, String label, String path, String sourceUri, String fallbackText) {
            this.kind = kind == null ? "OTHER" : kind;
            this.label = label == null ? "" : label;
            this.path = path == null ? "" : path;
            this.sourceUri = sourceUri == null ? "" : sourceUri;
            this.fallbackText = fallbackText == null ? "" : fallbackText;
        }
    }

    /**
     * Structured tool-call record rendered as an expandable Antigravity-style step in live chat
     * and restored conversations.
     */
    public static final class ToolCall {
        public final String id;
        public final String toolName;
        public final String title;
        public final String summary;
        public final String argumentsJson;
        public final String status;
        public final String resultText;
        public final boolean running;
        public final boolean pending;
        public final boolean succeeded;
        public final long durationMs;

        public ToolCall(String id, String toolName, String title, String summary, String argumentsJson,
                        String status, String resultText, boolean running, boolean pending,
                        boolean succeeded, long durationMs) {
            this.id = id == null ? "" : id;
            this.toolName = toolName == null ? "" : toolName;
            this.title = title == null ? "" : title;
            this.summary = summary == null ? "" : summary;
            this.argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
            this.status = status == null ? "" : status;
            this.resultText = resultText == null ? "" : resultText;
            this.running = running;
            this.pending = pending;
            this.succeeded = succeeded;
            this.durationMs = Math.max(0L, durationMs);
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
        /** True when this assistant reply was cut off by process death. */
        public final boolean interrupted;
        /** Persistence id for interrupted retries; null otherwise. */
        public final String turnId;
        /** Clickable attachment references for this turn (photos, PDF pages, documents, voice). */
        public final List<AttachmentRef> attachments;
        /** Expandable tool calls executed or proposed during this assistant turn. */
        public final List<ToolCall> toolCalls;

        public Turn(String role, String text, String reasoning, List<Source> sources, boolean streaming) {
            this(role, text, reasoning, sources, streaming, false, null, Collections.emptyList(), Collections.emptyList());
        }

        public Turn(String role, String text, String reasoning, List<Source> sources, boolean streaming,
                    boolean interrupted, String turnId) {
            this(role, text, reasoning, sources, streaming, interrupted, turnId, Collections.emptyList(), Collections.emptyList());
        }

        public Turn(String role, String text, String reasoning, List<Source> sources, boolean streaming,
                    boolean interrupted, String turnId, List<AttachmentRef> attachments) {
            this(role, text, reasoning, sources, streaming, interrupted, turnId, attachments, Collections.emptyList());
        }

        public Turn(String role, String text, String reasoning, List<Source> sources, boolean streaming,
                    boolean interrupted, String turnId, List<AttachmentRef> attachments, List<ToolCall> toolCalls) {
            this.role = Objects.requireNonNull(role, "role");
            this.text = text == null ? "" : text;
            this.reasoning = reasoning == null || reasoning.isEmpty() ? null : reasoning;
            List<Source> copy = sources == null ? Collections.emptyList() : new ArrayList<>(sources);
            this.sources = Collections.unmodifiableList(copy);
            this.streaming = streaming;
            this.interrupted = interrupted;
            this.turnId = turnId == null || turnId.isEmpty() ? null : turnId;
            this.attachments = attachments == null || attachments.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(attachments));
            this.toolCalls = toolCalls == null || toolCalls.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(toolCalls));
        }
    }

    public final List<Turn> turns;
    public final String status;
    public final boolean generating;
    public final List<String> activeDocumentNames;
    public final List<String> traceLines;
    public final List<AttachmentRef> activeDocuments;
    public final List<AttachmentRef> activeImages;

    public ChatSnapshot(List<Turn> turns, String status, boolean generating) {
        this(turns, status, generating, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    public ChatSnapshot(List<Turn> turns, String status, boolean generating,
                        List<String> activeDocumentNames, List<String> traceLines) {
        this(turns, status, generating, activeDocumentNames, traceLines,
                Collections.emptyList(), Collections.emptyList());
    }

    public ChatSnapshot(List<Turn> turns, String status, boolean generating,
                        List<String> activeDocumentNames, List<String> traceLines,
                        List<AttachmentRef> activeDocuments, List<AttachmentRef> activeImages) {
        List<Turn> copy = turns == null ? Collections.emptyList() : new ArrayList<>(turns);
        this.turns = Collections.unmodifiableList(copy);
        this.status = status == null ? "" : status;
        this.generating = generating;
        this.activeDocumentNames = activeDocumentNames == null
                ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(activeDocumentNames));
        this.traceLines = traceLines == null
                ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(traceLines));
        this.activeDocuments = activeDocuments == null || activeDocuments.isEmpty()
                ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(activeDocuments));
        this.activeImages = activeImages == null || activeImages.isEmpty()
                ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(activeImages));
    }
}
