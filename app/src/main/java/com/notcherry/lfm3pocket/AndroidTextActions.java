package com.notcherry.lfm3pocket;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;

/** Android clipboard and chooser adapters. Sharing opens UI and never sends on the user's behalf. */
final class AndroidTextActions implements TextActionController.Clipboard, TextActionController.ShareSheet {
    private final Context context;

    AndroidTextActions(Context context) {
        Context resolved = context;
        if (context != null) {
            try {
                Context application = context.getApplicationContext();
                if (application != null) resolved = application;
            } catch (RuntimeException ignored) {
                // Preserve default-deny behavior in unattached dependency contract tests.
            }
        }
        this.context = resolved;
    }

    @Override public void copy(String text) {
        if (context == null) throw new IllegalStateException("Android context is unavailable");
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) throw new IllegalStateException("Android clipboard is unavailable");
        clipboard.setPrimaryClip(ClipData.newPlainText("LFM3 Pocket", text));
    }

    @Override public void open(String text) {
        if (context == null) throw new IllegalStateException("Android context is unavailable");
        try {
            context.startActivity(createChooserIntent(text));
        } catch (ActivityNotFoundException e) {
            throw new TextActionController.NoShareSheetException();
        }
    }

    static Intent createChooserIntent(String text) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        return Intent.createChooser(send, "Share text")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
