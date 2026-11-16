package com.notcherry.lfm3pocket;

import java.util.Locale;

/**
 * Decides the foreground-service notification and the unload/wake side effects.
 * Call from one thread. Android wiring lives in {@link ModelRuntime}.
 */
public final class RuntimePolicy {
    public static final class Effects {
        public final RuntimeNotice notice;
        public final boolean wantService;
        public final boolean wantWake;
        public final boolean cancelTurn;
        public final boolean unloadModel;

        Effects(RuntimeNotice notice, boolean wantService, boolean wantWake, boolean cancelTurn, boolean unloadModel) {
            this.notice = notice;
            this.wantService = wantService;
            this.wantWake = wantWake;
            this.cancelTurn = cancelTurn;
            this.unloadModel = unloadModel;
        }
    }

    private boolean loading;
    private boolean modelLoaded;
    private boolean generating;
    private boolean synthesizing;
    private boolean playing;
    private boolean keepLoaded = true;
    private int idleMinutes;
    private boolean lockPreview;
    private int tokens;
    private int maxTokens = 1;
    private int windowTokens;
    private long windowStartMs = -1L;
    private double tokensPerSecond;
    private String question = "";
    private String modelName = "";
    private boolean batteryLow;
    private int thermal;
    private boolean unloadAfterTurn;
    private boolean refuseNewTurns;
    private int trimStrikesWhileGenerating;
    private boolean consent;
    private boolean consentSafe;
    private String consentText = "";
    private String proposalId = "";
    private long idleDeadlineMs = -1L;

    public boolean refuseNewTurns() { return refuseNewTurns; }
    public boolean modelLoaded() { return modelLoaded; }
    public boolean generating() { return generating; }

    public void reset() {
        loading = false;
        modelLoaded = false;
        generating = false;
        synthesizing = false;
        playing = false;
        tokens = 0;
        windowTokens = 0;
        windowStartMs = -1L;
        tokensPerSecond = 0;
        question = "";
        modelName = "";
        batteryLow = false;
        thermal = 0;
        unloadAfterTurn = false;
        refuseNewTurns = false;
        trimStrikesWhileGenerating = 0;
        consent = false;
        consentSafe = false;
        consentText = "";
        proposalId = "";
        idleDeadlineMs = -1L;
    }

    public Effects onSettings(boolean keep, int idle, boolean preview, long nowMs) {
        keepLoaded = keep;
        idleMinutes = idle == 15 || idle == 60 ? idle : 0;
        lockPreview = preview;
        if (modelLoaded && !generating && !busyAudio() && idleMinutes > 0 && idleDeadlineMs < 0)
            idleDeadlineMs = nowMs + idleMinutes * 60_000L;
        if (idleMinutes == 0) idleDeadlineMs = -1L;
        return view(false, false);
    }

    public Effects onLoading(String name) {
        loading = true;
        if (name != null && !name.isEmpty()) modelName = name;
        return view(false, false);
    }

    public Effects onLoaded(String name, long nowMs) {
        loading = false;
        modelLoaded = true;
        if (name != null && !name.isEmpty()) modelName = name;
        armIdle(nowMs);
        return view(false, false);
    }

    public Effects onLoadFailed() {
        loading = false;
        return view(false, false);
    }

    public Effects onGenerationStarted(String asked, int limit, long nowMs) {
        generating = true;
        tokens = 0;
        windowTokens = 0;
        windowStartMs = nowMs;
        tokensPerSecond = 0;
        maxTokens = Math.max(1, limit);
        question = asked == null ? "" : asked.trim();
        idleDeadlineMs = -1L;
        trimStrikesWhileGenerating = 0;
        return view(false, false);
    }

    public Effects refreshRate(long nowMs) {
        if (generating && windowStartMs >= 0) {
            long elapsed = nowMs - windowStartMs;
            if (elapsed >= 1000L && windowTokens > 0) {
                tokensPerSecond = windowTokens * 1000.0 / elapsed;
                windowStartMs = nowMs;
                windowTokens = 0;
            }
        }
        return view(false, false);
    }

    public Effects onTokens(int count, long nowMs) {
        if (count <= 0 || !generating) return view(false, false);
        tokens += count;
        if (windowStartMs < 0) windowStartMs = nowMs;
        windowTokens += count;
        long elapsed = nowMs - windowStartMs;
        if (elapsed >= 1000L) {
            tokensPerSecond = windowTokens * 1000.0 / elapsed;
            windowStartMs = nowMs;
            windowTokens = 0;
        }
        return view(false, false);
    }

    public Effects onGenerationFinished(long nowMs) {
        boolean was = generating;
        generating = false;
        trimStrikesWhileGenerating = 0;
        if (was) armIdle(nowMs);
        return finishIfNeeded(nowMs);
    }

    public Effects onSpeechStarted() {
        synthesizing = true;
        idleDeadlineMs = -1L;
        return view(false, false);
    }

    public Effects onPlaybackStarted() {
        synthesizing = false;
        playing = true;
        return view(false, false);
    }

    public Effects onSpeechFinished(long nowMs) {
        synthesizing = false;
        playing = false;
        if (modelLoaded) armIdle(nowMs);
        return finishIfNeeded(nowMs);
    }

    public Effects stopRequested(long nowMs) {
        boolean cancel = generating || synthesizing || playing;
        generating = false;
        synthesizing = false;
        playing = false;
        trimStrikesWhileGenerating = 0;
        if (modelLoaded) armIdle(nowMs);
        Effects base = finishIfNeeded(nowMs);
        return new Effects(base.notice, base.wantService, base.wantWake, cancel || base.cancelTurn, base.unloadModel);
    }

    public Effects unloadRequested() {
        return drop(true);
    }

    public Effects onClock(long nowMs) {
        if (idleDeadlineMs >= 0 && nowMs >= idleDeadlineMs && modelLoaded && !generating && !busyAudio() && !consent)
            return drop(false);
        return view(false, false);
    }

    /** PowerManager thermal status ordinal: 0 none, 1 light, 2 moderate, 3 severe, 4+ critical. */
    public Effects onThermal(int status, long nowMs) {
        thermal = status;
        if (status < 3) {
            unloadAfterTurn = false;
            refuseNewTurns = false;
            return view(false, false);
        }
        if (status >= 4) {
            refuseNewTurns = true;
            return drop(true);
        }
        refuseNewTurns = true;
        unloadAfterTurn = true;
        if (!generating && !busyAudio()) return drop(false);
        return view(false, false);
    }

    public Effects onBatteryLow(boolean low) {
        batteryLow = low;
        return view(false, false);
    }

    /** {@code complete} is TRIM_MEMORY_COMPLETE. Otherwise this is RUNNING_CRITICAL. */
    public Effects onTrim(boolean complete, long nowMs) {
        if (!modelLoaded && !generating && !loading) return view(false, false);
        if (complete) return drop(true);
        if (generating || busyAudio()) {
            trimStrikesWhileGenerating++;
            if (trimStrikesWhileGenerating >= 2) return drop(true);
            unloadAfterTurn = true;
            return view(false, false);
        }
        return drop(false);
    }

    public Effects onConsent(String id, String text, boolean safe) {
        consent = true;
        proposalId = id == null ? "" : id;
        consentText = text == null ? "" : text;
        consentSafe = safe;
        return view(false, false);
    }

    public Effects onConsentCleared(long nowMs) {
        consent = false;
        proposalId = "";
        consentText = "";
        consentSafe = false;
        if (modelLoaded && !generating && !busyAudio()) armIdle(nowMs);
        return view(false, false);
    }

    public Effects onKeepLoadedRelease(long nowMs) {
        if (keepLoaded) return view(false, false);
        return drop(true);
    }

    private Effects finishIfNeeded(long nowMs) {
        if (unloadAfterTurn && !generating && !busyAudio()) return drop(false);
        return view(false, false);
    }

    private Effects drop(boolean cancel) {
        loading = false;
        modelLoaded = false;
        generating = false;
        synthesizing = false;
        playing = false;
        unloadAfterTurn = false;
        idleDeadlineMs = -1L;
        trimStrikesWhileGenerating = 0;
        consent = false;
        return new Effects(RuntimeNotice.hidden(), false, false, cancel, true);
    }

    private void armIdle(long nowMs) {
        idleDeadlineMs = idleMinutes > 0 ? nowMs + idleMinutes * 60_000L : -1L;
    }

    private boolean busyAudio() { return synthesizing || playing; }

    private Effects view(boolean cancel, boolean unload) {
        boolean wake = generating || synthesizing || playing;
        boolean service = loading || modelLoaded || generating || busyAudio() || consent;
        return new Effects(notice(), service, wake, cancel, unload);
    }

    private RuntimeNotice notice() {
        if (!loading && !modelLoaded && !generating && !busyAudio() && !consent) return RuntimeNotice.hidden();
        String warning = warning();
        if (consent) {
            boolean fits = consentSafe && consentText.length() <= 400;
            String text = consentText.isEmpty() ? "Review the Android action." : consentText;
            if (!warning.isEmpty()) text = text + "\n" + warning;
            if (fits) {
                return RuntimeNotice.of(RuntimeNotice.Kind.APPROVAL, "Approval needed", text, false,
                        0, 0, proposalId, false,
                        RuntimeNotice.Action.APPROVE, RuntimeNotice.Action.REJECT, RuntimeNotice.Action.REVIEW);
            }
            return RuntimeNotice.of(RuntimeNotice.Kind.APPROVAL, "Approval needed", text, false,
                    0, 0, proposalId, false,
                    RuntimeNotice.Action.REVIEW, RuntimeNotice.Action.REJECT, RuntimeNotice.Action.OPEN);
        }
        if (generating) {
            String rate = tokensPerSecond > 0
                    ? String.format(Locale.US, "%.0f tok/s", tokensPerSecond) : "… tok/s";
            String body = tokens + " / " + maxTokens + " tokens · " + rate;
            if (!question.isEmpty()) body = clip(question, 80) + "\n" + body;
            if (!warning.isEmpty()) body = body + "\n" + warning;
            int shown = Math.min(tokens, maxTokens);
            return RuntimeNotice.of(RuntimeNotice.Kind.GENERATING, "Generating", body, lockPreview,
                    shown, maxTokens, "", false, RuntimeNotice.Action.STOP, RuntimeNotice.Action.OPEN);
        }
        if (busyAudio()) {
            String body = playing ? "Playing the spoken reply" : "Generating the spoken reply";
            if (!warning.isEmpty()) body = body + "\n" + warning;
            return RuntimeNotice.of(RuntimeNotice.Kind.SPEAKING, "Speaking", body, lockPreview,
                    0, 0, "", playing, RuntimeNotice.Action.STOP, RuntimeNotice.Action.OPEN);
        }
        if (loading && !modelLoaded) {
            String body = "Loading the model into memory";
            if (!warning.isEmpty()) body = body + "\n" + warning;
            return RuntimeNotice.of(RuntimeNotice.Kind.LOADING, "Loading model", body, false,
                    0, 0, "", false, RuntimeNotice.Action.OPEN);
        }
        String body = "Model loaded · using memory";
        if (!modelName.isEmpty()) body = body + "\n" + modelName;
        if (idleMinutes > 0) body = body + "\nUnloads after " + idleMinutes + " idle minutes";
        if (!warning.isEmpty()) body = body + "\n" + warning;
        return RuntimeNotice.of(RuntimeNotice.Kind.RESIDENT, "Model loaded", body, false,
                0, 0, "", false, RuntimeNotice.Action.UNLOAD, RuntimeNotice.Action.OPEN);
    }

    private String warning() {
        String heat = thermal >= 4 ? "Phone is too hot"
                : thermal >= 3 ? "Phone is very warm"
                : thermal >= 2 ? "Phone is warm" : "";
        String battery = batteryLow ? "Battery low" : "";
        if (heat.isEmpty()) return battery;
        if (battery.isEmpty()) return heat;
        return heat + " · " + battery;
    }

    private static String clip(String value, int max) {
        String one = value.replace('\n', ' ').trim();
        return one.length() <= max ? one : one.substring(0, max - 1) + "…";
    }
}
