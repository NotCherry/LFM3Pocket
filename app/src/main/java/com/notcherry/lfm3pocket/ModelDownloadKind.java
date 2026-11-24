package com.notcherry.lfm3pocket;

/** Identifies a managed large-model download job shown in Settings. */
public enum ModelDownloadKind {
    CACTUS_WHISTLE,
    TINY_VOCA;

    public String workUniqueName() {
        return "model_download_" + name().toLowerCase();
    }

    public int notificationId() {
        return 40_000 + ordinal();
    }
}