package com.notcherry.lfm3pocket;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in notification listener. Captures active notification snapshots only while system-bound.
 * Access is additionally gated by app-level master toggle and per-app allowlist at tool execution.
 */
public final class HanamiNotificationListenerService extends NotificationListenerService {
    private static final AtomicReference<List<NotificationAccess.Entry>> SNAPSHOT =
            new AtomicReference<>(Collections.emptyList());
    private static volatile boolean connected;

    public static boolean isSystemEnabled(Context context) {
        if (context == null) return false;
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName me = new ComponentName(context, HanamiNotificationListenerService.class);
        String flat = me.flattenToString();
        String shortFlat = me.flattenToShortString();
        String[] parts = enabled.split(":");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            if (value.equals(flat) || value.equals(shortFlat)) return true;
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component != null && component.equals(me)) return true;
        }
        return false;
    }

    /** Returns the latest snapshot, or null when the listener is not currently bound/available. */
    public static List<NotificationAccess.Entry> snapshotOrNull() {
        if (!connected) return null;
        List<NotificationAccess.Entry> current = SNAPSHOT.get();
        return current == null ? Collections.emptyList() : current;
    }

    static void setConnectedForTest(boolean value) {
        connected = value;
    }

    static void setSnapshotForTest(List<NotificationAccess.Entry> entries) {
        if (entries == null) SNAPSHOT.set(Collections.emptyList());
        else SNAPSHOT.set(Collections.unmodifiableList(new ArrayList<>(entries)));
    }

    static void clearForTest() {
        connected = false;
        SNAPSHOT.set(Collections.emptyList());
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        refreshSnapshot();
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        SNAPSHOT.set(Collections.emptyList());
        super.onListenerDisconnected();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        refreshSnapshot();
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        refreshSnapshot();
    }

    private void refreshSnapshot() {
        if (!connected) {
            SNAPSHOT.set(Collections.emptyList());
            return;
        }
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException e) {
            SNAPSHOT.set(Collections.emptyList());
            return;
        }
        if (active == null || active.length == 0) {
            SNAPSHOT.set(Collections.emptyList());
            return;
        }
        ArrayList<NotificationAccess.Entry> entries = new ArrayList<>(active.length);
        for (StatusBarNotification sbn : active) {
            if (sbn == null) continue;
            Notification notification = sbn.getNotification();
            Bundle extras = notification == null ? null : notification.extras;
            String pkg = sbn.getPackageName();
            String title = charSequence(extras, Notification.EXTRA_TITLE);
            if (title.isEmpty()) title = charSequence(extras, Notification.EXTRA_CONVERSATION_TITLE);
            String text = charSequence(extras, Notification.EXTRA_TEXT);
            if (text.isEmpty()) text = charSequence(extras, Notification.EXTRA_BIG_TEXT);
            if (text.isEmpty()) text = charSequence(extras, Notification.EXTRA_SUMMARY_TEXT);
            if (text.isEmpty()) text = charSequence(extras, Notification.EXTRA_SUB_TEXT);
            CharSequence[] lines = extras == null ? null : extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (text.isEmpty() && lines != null && lines.length > 0) {
                StringBuilder joined = new StringBuilder();
                for (CharSequence line : lines) {
                    if (line == null) continue;
                    if (joined.length() > 0) joined.append(" | ");
                    joined.append(line);
                }
                text = joined.toString();
            }
            entries.add(new NotificationAccess.Entry(pkg, title, text));
        }
        SNAPSHOT.set(Collections.unmodifiableList(entries));
    }

    private static String charSequence(Bundle extras, String key) {
        if (extras == null || key == null) return "";
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString();
    }
}
