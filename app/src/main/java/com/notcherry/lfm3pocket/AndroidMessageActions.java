package com.notcherry.lfm3pocket;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Opens SMS/share composer intents. No silent send; user completes in the target app. */
final class AndroidMessageActions {
    private final Context context;

    AndroidMessageActions(Context context) {
        Context resolved = context;
        if (context != null) {
            try {
                Context application = context.getApplicationContext();
                if (application != null) resolved = application;
            } catch (RuntimeException ignored) {
                // keep null/default-deny behavior
            }
        }
        this.context = resolved;
    }

    void openSmsComposer(String to, String body) {
        if (context == null) throw new IllegalStateException("Android context is unavailable");
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        String smsto = "smsto:" + (to == null ? "" : to);
        intent.setData(Uri.parse(smsto));
        if (body != null) {
            intent.putExtra("sms_body", body);
            intent.putExtra(Intent.EXTRA_TEXT, body);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            throw new IllegalStateException("No SMS app available");
        }
    }

    void openShareToPackage(String packageName, String body) {
        if (context == null) throw new IllegalStateException("Android context is unavailable");
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        if (body != null) intent.putExtra(Intent.EXTRA_TEXT, body);
        intent.setPackage(packageName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            throw new IllegalStateException("Target app not available");
        }
    }
}
