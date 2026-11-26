package com.notcherry.lfm3pocket;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Local-only crash evidence: last uncaught Java exception plus Android 11+
 * {@link ApplicationExitInfo} reasons. Never uploads.
 */
public final class CrashLog {
    static final String FILE_NAME = "crash-log.txt";
    private static final String PREFS_NAME = "hanami_crash_log";
    private static final String KEY_LAST_EXIT_TS = "last_exit_timestamp";
    private static final int MAX_CHARS = 48_000;
    private static final Object LOCK = new Object();

    static final class ExitEntry {
        final String reason;
        final int status;
        final int importance;
        final long timestamp;
        final String description;
        final String trace;

        ExitEntry(String reason, int status, int importance, long timestamp, String description, String trace) {
            this.reason = reason;
            this.status = status;
            this.importance = importance;
            this.timestamp = timestamp;
            this.description = description;
            this.trace = trace;
        }
    }

    private CrashLog() {}

    /** Install process-wide. Safe to call more than once. */
    public static void install(Context context) {
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                recordUncaught(app, thread, error);
            } catch (Throwable ignored) {
                // Crash logging must never replace the original failure.
            }
            if (previous != null) previous.uncaughtException(thread, error);
            else {
                System.err.println(error);
                System.exit(10);
            }
        });
    }

    /** Append only unseen process-exit reasons (API 30+). No-op on older devices or when unchanged. */
    public static void captureExitInfo(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager am = context.getSystemService(ActivityManager.class);
            if (am == null) return;
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 8);
            if (exits == null || exits.isEmpty()) return;
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long lastTs = prefs.getLong(KEY_LAST_EXIT_TS, 0L);
            if (lastTs == 0L) {
                lastTs = maxLoggedExitTimestamp(read(context));
            }
            List<ExitEntry> unseen = new ArrayList<>();
            for (ApplicationExitInfo info : exits) {
                if (info.getTimestamp() <= lastTs) continue;
                String trace = null;
                if (Build.VERSION.SDK_INT >= 31 && info.getTraceInputStream() != null) {
                    trace = readLimited(info.getTraceInputStream(), 8_000);
                }
                unseen.add(new ExitEntry(
                        reasonLabel(info.getReason()),
                        info.getStatus(),
                        info.getImportance(),
                        info.getTimestamp(),
                        nullToEmpty(info.getDescription()),
                        trace));
            }
            long updatedTs = recordNewExits(file(context), lastTs, unseen);
            if (updatedTs > prefs.getLong(KEY_LAST_EXIT_TS, 0L)) {
                prefs.edit().putLong(KEY_LAST_EXIT_TS, updatedTs).apply();
            }
        } catch (Throwable ignored) {
            // Exit-info collection is best-effort.
        }
    }

    /**
     * Test seam: append only exit entries newer than {@code lastRecordedTimestamp} and return
     * the updated high-water timestamp. Returns {@code lastRecordedTimestamp} without writing
     * when no new entries exist.
     */
    static long recordNewExits(File target, long lastRecordedTimestamp, List<ExitEntry> exits) {
        if (exits == null || exits.isEmpty()) return lastRecordedTimestamp;
        long maxTs = lastRecordedTimestamp;
        StringBuilder block = new StringBuilder();
        int count = 0;
        for (ExitEntry info : exits) {
            if (info == null || info.timestamp <= lastRecordedTimestamp) continue;
            if (count == 0) {
                block.append("--- process exits @ ").append(isoNow()).append(" ---\n");
            }
            count++;
            if (info.timestamp > maxTs) maxTs = info.timestamp;
            block.append("reason=").append(nullToEmpty(info.reason))
                    .append(" status=").append(info.status)
                    .append(" importance=").append(info.importance)
                    .append(" timestamp=").append(info.timestamp)
                    .append(" description=").append(nullToEmpty(info.description))
                    .append('\n');
            if (AndroidPermissionGate.isAndroid17MemoryLimiterExit(info.description)) {
                block.append("note=Android 17 MemoryLimiter terminated the app; try a smaller quantization or lower context length.\n");
            }
            if (info.trace != null && !info.trace.isBlank()) {
                block.append("trace:\n").append(info.trace).append('\n');
            }
        }
        if (count > 0) {
            appendFile(target, block.toString());
        }
        return maxTs;
    }

    /** Parse the largest recorded {@code timestamp=} value from an existing crash log. */
    static long maxLoggedExitTimestamp(String logText) {
        if (logText == null || logText.isEmpty()) return 0L;
        long maxTs = 0L;
        final String needle = "timestamp=";
        int idx = 0;
        while ((idx = logText.indexOf(needle, idx)) >= 0) {
            int start = idx + needle.length();
            int end = start;
            while (end < logText.length() && Character.isDigit(logText.charAt(end))) {
                end++;
            }
            if (end > start) {
                try {
                    long ts = Long.parseLong(logText.substring(start, end));
                    if (ts > maxTs) maxTs = ts;
                } catch (NumberFormatException ignored) {
                }
            }
            idx = end;
        }
        return maxTs;
    }

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static String read(Context context) {
        return readFile(file(context));
    }

    public static void clear(Context context) {
        clearFile(file(context));
    }

    /** Writes a shareable copy under cache and returns it. */
    public static File exportCopy(Context context) throws Exception {
        String body = read(context);
        if (body.isEmpty()) body = "No crash evidence recorded yet.\n";
        File out = new File(context.getCacheDir(), "hanami-crash-log.txt");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(out, false),
                StandardCharsets.UTF_8)) {
            writer.write(body);
        }
        return out;
    }

    static void recordUncaught(Context context, Thread thread, Throwable error) {
        recordUncaught(file(context), thread, error);
    }

    /** Test seam: write an uncaught block into an arbitrary crash-log file. */
    static void recordUncaught(File target, Thread thread, Throwable error) {
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        String block = "--- uncaught @ " + isoNow() + " ---\n"
                + "thread=" + (thread == null ? "?" : thread.getName()) + "\n"
                + stack + "\n";
        appendFile(target, block);
    }

    static String readFile(File target) {
        if (target == null || !target.isFile()) return "";
        try {
            return readLimited(new FileInputStream(target), MAX_CHARS);
        } catch (Exception e) {
            return "";
        }
    }

    static void clearFile(File target) {
        synchronized (LOCK) {
            if (target != null) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
        }
    }

    private static void appendFile(File target, String block) {
        synchronized (LOCK) {
            try {
                if (target == null) return;
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) return;
                String existing = target.isFile() ? readLimited(new FileInputStream(target), MAX_CHARS) : "";
                String merged = block + existing;
                if (merged.length() > MAX_CHARS) merged = merged.substring(0, MAX_CHARS);
                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(target, false),
                        StandardCharsets.UTF_8)) {
                    writer.write(merged);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static String readLimited(java.io.InputStream in, int maxChars) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) >= 0 && out.length() < maxChars) {
                int take = Math.min(n, maxChars - out.length());
                out.append(buf, 0, take);
            }
        }
        return out.toString();
    }

    private static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String reasonLabel(int reason) {
        if (Build.VERSION.SDK_INT < 30) return String.valueOf(reason);
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INIT_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            case ApplicationExitInfo.REASON_FREEZER: return "FREEZER";
            default: return "REASON_" + reason;
        }
    }
}
