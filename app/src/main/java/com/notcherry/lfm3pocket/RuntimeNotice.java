package com.notcherry.lfm3pocket;

/** What the ongoing model notification should show. Plain data so tests need no Android. */
public final class RuntimeNotice {
    public enum Kind { HIDDEN, LOADING, GENERATING, SPEAKING, LISTENING, RESIDENT, APPROVAL }
    public enum Action { STOP, UNLOAD, OPEN, ASK, TALK, REVIEW, APPROVE, REJECT }

    public final Kind kind;
    public final String title;
    public final String text;
    public final Action[] actions;
    public final boolean publicLockScreen;
    public final int progress;
    public final int progressMax;
    public final String proposalId;
    /** True while spoken audio is actually playing, so the service may add the mediaPlayback type. */
    public final boolean mediaPlayback;
    /** True while a voice-action microphone capture window is active. */
    public final boolean microphoneCapture;

    private RuntimeNotice(Kind kind, String title, String text, Action[] actions, boolean publicLockScreen,
                          int progress, int progressMax, String proposalId, boolean mediaPlayback,
                          boolean microphoneCapture) {
        this.kind = kind;
        this.title = title;
        this.text = text;
        this.actions = actions;
        this.publicLockScreen = publicLockScreen;
        this.progress = progress;
        this.progressMax = progressMax;
        this.proposalId = proposalId == null ? "" : proposalId;
        this.mediaPlayback = mediaPlayback;
        this.microphoneCapture = microphoneCapture;
    }

    static RuntimeNotice hidden() {
        return new RuntimeNotice(Kind.HIDDEN, "", "", new Action[0], false, 0, 0, "", false, false);
    }

    static RuntimeNotice of(Kind kind, String title, String text, boolean publicLockScreen,
                            int progress, int progressMax, String proposalId, boolean mediaPlayback,
                            Action... actions) {
        return of(kind, title, text, publicLockScreen, progress, progressMax, proposalId, mediaPlayback, false, actions);
    }

    static RuntimeNotice of(Kind kind, String title, String text, boolean publicLockScreen,
                            int progress, int progressMax, String proposalId, boolean mediaPlayback,
                            boolean microphoneCapture, Action... actions) {
        return new RuntimeNotice(kind, title, text, actions, publicLockScreen, progress, progressMax,
                proposalId, mediaPlayback, microphoneCapture);
    }
}
