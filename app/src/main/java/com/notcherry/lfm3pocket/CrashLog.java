package com.notcherry.lfm3pocket;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
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
    private static final int MAX_CHARS = 48_000;
    private static final Object LOCK = new Object();

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

    /** Append recent process-exit reasons (API 30+). No-op on older devices. */
    public static void captureExitInfo(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager am = context.getSystemService(ActivityManager.class);
            if (am == null) return;
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 8);
            if (exits == null || exits.isEmpty()) return;
            StringBuilder block = new StringBuilder();
            block.append("--- process exits @ ").append(isoNow()).append(" ---\n");
            for (ApplicationExitInfo info : exits) {
                block.append("reason=").append(reasonLabel(info.getReason()))
                        .append(" status=").append(info.getStatus())
                        .append(" importance=").append(info.getImportance())
                        .append(" timestamp=").append(info.getTimestamp())
                        .append(" description=").append(nullToEmpty(info.getDescription()))
                        .append('\n');
                if (Build.VERSION.SDK_INT >= 31) {
                    String trace = info.getTraceInputStream() == null ? null
                            : readLimited(info.getTraceInputStream(), 8_000);
                    if (trace != null && !trace.isBlank()) {
                        block.append("trace:\n").append(trace).append('\n');
                    }
                }
            }
            append(context, block.toString());
        } catch (Throwable ignored) {
            // Exit-info collection is best-effort.
        }
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

    private static void append(Context context, String block) {
        appendFile(file(context), block);
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
