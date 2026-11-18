package com.notcherry.lfm3pocket;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executes list_notifications against an opt-in, allowlisted notification snapshot. */
final class NotificationListActionExecutor implements AndroidActionExecutor {
    interface SnapshotProvider {
        /** Returns active notifications or null when the listener is unavailable/unbound. */
        List<NotificationAccess.Entry> activeNotifications();
    }

    private final RuntimePreferences preferences;
    private final SnapshotProvider snapshotProvider;
    private final java.util.function.BooleanSupplier systemAccessEnabled;

    NotificationListActionExecutor(RuntimePreferences preferences,
                                   SnapshotProvider snapshotProvider,
                                   java.util.function.BooleanSupplier systemAccessEnabled) {
        if (preferences == null || snapshotProvider == null || systemAccessEnabled == null) {
            throw new IllegalArgumentException("Missing notification access dependencies");
        }
        this.preferences = preferences;
        this.snapshotProvider = snapshotProvider;
        this.systemAccessEnabled = systemAccessEnabled;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (!"list_notifications".equals(approvedToolName)) {
            return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
        }
        if (!preferences.notificationAccessEnabled()) {
            return new ActionResult(false, "NOTIFICATION_ACCESS_DISABLED",
                    "Notification access is off. Enable it in Settings and grant system notification access.");
        }
        Set<String> allowlist = preferences.notificationAllowlist();
        if (allowlist.isEmpty()) {
            return new ActionResult(false, "NOTIFICATION_ALLOWLIST_EMPTY",
                    "No apps are allowed for notification access. Add at least one app in Settings.");
        }
        boolean systemEnabled;
        try {
            systemEnabled = systemAccessEnabled.getAsBoolean();
        } catch (RuntimeException e) {
            systemEnabled = false;
        }
        if (!systemEnabled) {
            return new ActionResult(false, "NOTIFICATION_LISTENER_DISABLED",
                    "System notification access is not enabled for Hanami. Open system settings to enable it.");
        }
        List<NotificationAccess.Entry> snapshot;
        try {
            snapshot = snapshotProvider.activeNotifications();
        } catch (RuntimeException e) {
            snapshot = null;
        }
        if (snapshot == null) {
            return new ActionResult(false, "NOTIFICATION_LISTENER_UNBOUND",
                    "Notification listener is not available. Enable system notification access and try again.");
        }
        int limit = NotificationAccess.DEFAULT_LIMIT;
        String packageFilter = "";
        if (arguments != null) {
            String rawLimit = arguments.get("limit");
            if (rawLimit != null && !rawLimit.isEmpty()) {
                try { limit = Integer.parseInt(rawLimit); }
                catch (NumberFormatException ignored) { limit = NotificationAccess.DEFAULT_LIMIT; }
            }
            String rawPackage = arguments.get("package");
            if (rawPackage != null) packageFilter = rawPackage;
        }
        if (!packageFilter.isEmpty() && !allowlist.contains(packageFilter)) {
            return new ActionResult(false, "PACKAGE_NOT_ALLOWED",
                    "The requested package is not in the notification allowlist.");
        }
        List<NotificationAccess.Entry> filtered = NotificationAccess.filter(snapshot, allowlist, packageFilter, limit);
        return new ActionResult(true, "NOTIFICATIONS_LISTED", NotificationAccess.formatResult(filtered, packageFilter));
    }
}
