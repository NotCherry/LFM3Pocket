package com.notcherry.lfm3pocket;

/** What the ongoing model notification should show. Plain data so tests need no Android. */
public final class RuntimeNotice {
    public enum Kind { HIDDEN, LOADING, GENERATING, SPEAKING, RESIDENT, APPROVAL }
    public enum Action { STOP, UNLOAD, OPEN, ASK, REVIEW, APPROVE, REJECT }

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

    private RuntimeNotice(Kind kind, String title, String text, Action[] actions, boolean publicLockScreen,
                          int progress, int progressMax, String proposalId, boolean mediaPlayback) {
        this.kind = kind;
        this.title = title;
        this.text = text;
        this.actions = actions;
        this.publicLockScreen = publicLockScreen;
        this.progress = progress;
        this.progressMax = progressMax;
        this.proposalId = proposalId == null ? "" : proposalId;
        this.mediaPlayback = mediaPlayback;
    }

    static RuntimeNotice hidden() {
        return new RuntimeNotice(Kind.HIDDEN, "", "", new Action[0], false, 0, 0, "", false);
    }

    static RuntimeNotice of(Kind kind, String title, String text, boolean publicLockScreen,
                            int progress, int progressMax, String proposalId, boolean mediaPlayback,
                            Action... actions) {
        return new RuntimeNotice(kind, title, text, actions, publicLockScreen, progress, progressMax,
                proposalId, mediaPlayback);
    }
}
