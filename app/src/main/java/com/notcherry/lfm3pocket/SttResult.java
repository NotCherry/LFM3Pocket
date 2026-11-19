package com.notcherry.lfm3pocket;

/** Result of a local speech-to-text transcription attempt. */
public final class SttResult {
    public enum Status {
        OK,
        EMPTY,
        CANCELLED,
        UNAVAILABLE,
        ERROR
    }

    public final Status status;
    public final String text;
    public final String message;

    private SttResult(Status status, String text, String message) {
        this.status = status == null ? Status.ERROR : status;
        this.text = text == null ? "" : text;
        this.message = message == null ? "" : message;
    }

    public static SttResult ok(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return empty("No speech detected");
        return new SttResult(Status.OK, value, "");
    }

    public static SttResult empty(String message) {
        return new SttResult(Status.EMPTY, "", message);
    }

    public static SttResult cancelled() {
        return new SttResult(Status.CANCELLED, "", "Transcription cancelled");
    }

    public static SttResult unavailable(String message) {
        return new SttResult(Status.UNAVAILABLE, "", message);
    }

    public static SttResult error(String message) {
        return new SttResult(Status.ERROR, "", message);
    }

    public boolean isOk() { return status == Status.OK && !text.isEmpty(); }
}
