package com.notcherry.lfm3pocket;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Ongoing notification for the model runtime. Uses the platform template so the
 * shade picks text color. No custom RemoteViews, so a dark status bar cannot
 * end up with dark Stillwater ink on a dark notification.
 */
final class RuntimeNotifications {
    static final String CHANNEL_ID = HanamiNotifications.MODEL_RUNTIME_CHANNEL_ID;
    static final int NOTIFICATION_ID = 1001;
    private static final int CANDLE = 0xFFE6C27A;

    private RuntimeNotifications() {}

    static void ensureChannel(Context context) {
        HanamiNotifications.ensureChannels(context);
    }

    static Notification build(Context context, RuntimeNotice notice, String conversationId, String title) {
        ensureChannel(context);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_stat_runtime)
                .setContentTitle(notice.title)
                .setContentText(firstLine(notice.text))
                .setStyle(new Notification.BigTextStyle().bigText(notice.text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(CANDLE)
                .setColorized(false)
                .setCategory(notice.kind == RuntimeNotice.Kind.GENERATING
                        ? Notification.CATEGORY_PROGRESS : Notification.CATEGORY_SERVICE)
                .setVisibility(notice.publicLockScreen
                        ? Notification.VISIBILITY_PUBLIC : Notification.VISIBILITY_PRIVATE)
                .setContentIntent(openChat(context, conversationId, title))
                .setDeleteIntent(service(context, ModelRuntimeService.ACTION_UNLOAD, null, 1));
        if (notice.progressMax > 0) builder.setProgress(notice.progressMax, notice.progress, false);
        int code = 10;
        for (RuntimeNotice.Action action : notice.actions) {
            builder.addAction(actionOf(context, action, notice.proposalId, conversationId, title, code++));
        }
        return builder.build();
    }

    private static Notification.Action actionOf(Context context, RuntimeNotice.Action action, String proposalId,
                                                String conversationId, String title, int code) {
        switch (action) {
            case STOP:
                return new Notification.Action.Builder(null, "Stop",
                        service(context, ModelRuntimeService.ACTION_STOP, null, code)).build();
            case UNLOAD:
                return new Notification.Action.Builder(null, "Unload",
                        service(context, ModelRuntimeService.ACTION_UNLOAD, null, code)).build();
            case OPEN:
                return new Notification.Action.Builder(null, "Open",
                        openChat(context, conversationId, title)).build();
            case REVIEW:
                return new Notification.Action.Builder(null, "Review",
                        review(context, proposalId, code)).build();
            case APPROVE:
                return new Notification.Action.Builder(null, "Approve",
                        service(context, ModelRuntimeService.ACTION_APPROVE, proposalId, code)).build();
            case REJECT:
                return new Notification.Action.Builder(null, "Reject",
                        service(context, ModelRuntimeService.ACTION_REJECT, proposalId, code)).build();
            default:
                return new Notification.Action.Builder(null, "Open",
                        openChat(context, conversationId, title)).build();
        }
    }

    private static PendingIntent openChat(Context context, String conversationId, String title) {
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_HISTORY_CONVERSATION_ID, conversationId == null ? "" : conversationId)
                .putExtra(MainActivity.EXTRA_HISTORY_TITLE, title == null ? "" : title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(context, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent review(Context context, String proposalId, int code) {
        Intent intent = new Intent(context, ToolConfirmationActivity.class)
                .putExtra("proposal_id", proposalId == null ? "" : proposalId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent service(Context context, String action, String proposalId, int code) {
        Intent intent = new Intent(context, ModelRuntimeService.class).setAction(action);
        if (proposalId != null) intent.putExtra(ModelRuntimeService.EXTRA_PROPOSAL_ID, proposalId);
        return PendingIntent.getService(context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String firstLine(String text) {
        if (text == null || text.isEmpty()) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }
}
