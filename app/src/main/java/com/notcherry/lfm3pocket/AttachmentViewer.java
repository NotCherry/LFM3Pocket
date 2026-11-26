package com.notcherry.lfm3pocket;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Opens attached photos, rendered PDF pages, audio recordings, and documents using the device's
 * default Android viewer ({@link Intent#ACTION_VIEW} + {@link FileProvider}) so Hanami stays lean.
 */
public final class AttachmentViewer {
    private AttachmentViewer() {}

    /**
     * Opens an attachment reference in an external Android viewer.
     *
     * @return {@code null} on success, or a concise user-facing error message on failure.
     */
    public static String open(Context context, ChatSnapshot.AttachmentRef ref) {
        if (context == null || ref == null) {
            return "Attachment is no longer available.";
        }
        String mime = mimeTypeFor(ref.kind, !ref.path.isEmpty() ? ref.path : ref.label);
        if (!ref.path.isEmpty()) {
            File file = new File(ref.path);
            if (file.isFile() && file.length() > 0) {
                return openLocalFile(context, file, mime);
            }
        }
        if (!ref.sourceUri.isEmpty() && ref.sourceUri.startsWith("content:")) {
            try {
                Uri uri = Uri.parse(ref.sourceUri);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, mime);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return null;
            } catch (ActivityNotFoundException e) {
                return context.getString(R.string.attachment_no_viewer);
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Temporary SAF grant expired — fall through to extracted text fallback if available.
            }
        }
        if (!ref.fallbackText.isEmpty()) {
            File preview = writeFallbackTextFile(context, ref.label, ref.fallbackText);
            if (preview != null) {
                return openLocalFile(context, preview, "text/plain");
            }
        }
        return context.getString(R.string.attachment_unavailable);
    }

    /** Convenience helper for pending composer attachments backed by a local file. */
    public static String openFile(Context context, String path, String label, String kind) {
        return open(context, new ChatSnapshot.AttachmentRef(kind, label, path, "", ""));
    }

    private static String openLocalFile(Context context, File file, String mime) {
        try {
            String authority = context.getPackageName() + ".fileprovider";
            Uri uri = FileProvider.getUriForFile(context, authority, file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return null;
        } catch (ActivityNotFoundException e) {
            return context.getString(R.string.attachment_no_viewer);
        } catch (IllegalArgumentException e) {
            return context.getString(R.string.attachment_unavailable);
        }
    }

    private static File writeFallbackTextFile(Context context, String label, String text) {
        try {
            File dir = new File(context.getCacheDir(), "view");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            String base = sanitizeFileName(label);
            if (!base.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                base = base + ".txt";
            }
            File out = new File(dir, base);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            return out;
        } catch (IOException ignored) {
            return null;
        }
    }

    static String sanitizeFileName(String name) {
        String raw = name == null ? "" : name.trim();
        if (raw.isEmpty()) return "attachment";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), 64));
        for (int i = 0; i < raw.length() && sb.length() < 64; i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String cleaned = sb.toString().replaceAll("^\\.+", "");
        return cleaned.isEmpty() ? "attachment" : cleaned;
    }

    static String mimeTypeFor(String kind, String nameOrPath) {
        String lower = nameOrPath == null ? "" : nameOrPath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".img")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".xml")) return "text/xml";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")
                || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".java")
                || lower.endsWith(".kt") || lower.endsWith(".py") || lower.endsWith(".js")
                || lower.endsWith(".ts") || lower.endsWith(".sh") || lower.endsWith(".c")
                || lower.endsWith(".cpp") || lower.endsWith(".h")) {
            return "text/plain";
        }
        if ("PHOTO".equalsIgnoreCase(kind)) return "image/*";
        if ("AUDIO".equalsIgnoreCase(kind)) return "audio/*";
        if ("DOC".equalsIgnoreCase(kind)) return "text/plain";
        return "*/*";
    }
}
