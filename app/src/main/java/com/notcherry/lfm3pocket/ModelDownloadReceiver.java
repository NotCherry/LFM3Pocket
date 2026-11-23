package com.notcherry.lfm3pocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles notification Cancel for in-flight model downloads. */
public final class ModelDownloadReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ModelDownloadNotifications.ACTION_CANCEL.equals(intent.getAction())) return;
        String raw = intent.getStringExtra(ModelDownloadNotifications.EXTRA_KIND);
        if (raw == null) return;
        try {
            ModelDownloadKind kind = ModelDownloadKind.valueOf(raw);
            ModelDownloadManager.getInstance(context).cancel(kind);
        } catch (IllegalArgumentException ignored) {
            // Unknown kind from an old notification after an app upgrade.
        }
    }
}