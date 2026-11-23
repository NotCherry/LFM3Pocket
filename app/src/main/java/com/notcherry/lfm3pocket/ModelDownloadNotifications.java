package com.notcherry.lfm3pocket;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Progress and cancel action for background model downloads. */
public final class ModelDownloadNotifications {
    public static final String ACTION_CANCEL = "com.notcherry.lfm3pocket.action.MODEL_DOWNLOAD_CANCEL";
    public static final String EXTRA_KIND = "kind";

    private ModelDownloadNotifications() {}

    public static void showProgress(Context context, ModelDownloadKind kind, String title, String body, int percent) {
        HanamiNotifications.ensureChannels(context);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
                HanamiNotifications.MODEL_DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_runtime)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(buildCancelAction(context, kind));
        if (percent >= 0) {
            builder.setProgress(100, percent, false);
        } else {
            builder.setProgress(0, 0, true);
        }
        try {
            NotificationManagerCompat.from(context).notify(kind.notificationId(), builder.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS may be denied; download continues without shade progress.
        }
    }

    public static void dismiss(Context context, ModelDownloadKind kind) {
        NotificationManagerCompat.from(context).cancel(kind.notificationId());
    }

    public static NotificationCompat.Action buildCancelAction(Context context, ModelDownloadKind kind) {
        Intent intent = new Intent(context, ModelDownloadReceiver.class);
        intent.setAction(ACTION_CANCEL);
        intent.putExtra(EXTRA_KIND, kind.name());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, kind.ordinal(), intent, flags);
        return new NotificationCompat.Action(0, context.getString(R.string.model_download_cancel), pending);
    }
}