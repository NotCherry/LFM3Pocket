package com.notcherry.lfm3pocket;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * Durable copies of chat photo attachments under app-private files.
 * Cache {@code photo-*} temps stay ephemeral; these survive process death for retry.
 */
public final class HistoryMediaStore {
    private static final String ROOT = "history-media";

    private HistoryMediaStore() {}

    /** Copies {@code sourcePath} into durable storage; returns the new absolute path. */
    public static String persistPhoto(Context context, String sourcePath, String conversationId, String turnId)
            throws IOException {
        if (sourcePath == null || sourcePath.isBlank()) return "";
        File source = new File(sourcePath);
        if (!source.isFile()) throw new IOException("Photo attachment is missing");
        File dir = conversationDir(context, conversationId);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create history media directory");
        String name = (turnId == null || turnId.isBlank() ? "photo" : turnId) + ".img";
        File dest = new File(dir, name);
        copyFile(source, dest);
        return dest.getAbsolutePath();
    }

    public static boolean deleteConversation(Context context, String conversationId) {
        return deleteTree(conversationDir(context, conversationId));
    }

    public static boolean deleteAll(Context context) {
        return deleteTree(new File(context.getFilesDir(), ROOT));
    }

    private static File conversationDir(Context context, String conversationId) {
        String safe = conversationId == null ? "unknown" : conversationId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(new File(context.getFilesDir(), ROOT), safe);
    }

    static void copyFile(File source, File dest) throws IOException {
        File tmp = new File(dest.getAbsolutePath() + ".partial");
        try (FileChannel in = new FileInputStream(source).getChannel();
             FileChannel out = new FileOutputStream(tmp).getChannel()) {
            long size = in.size();
            long copied = 0L;
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(64 * 1024);
            while (copied < size) {
                long transferred = in.transferTo(copied, size - copied, out);
                if (transferred > 0L) {
                    copied += transferred;
                    continue;
                }
                // transferTo is allowed to make zero progress. Fall back to an
                // explicit positional read/write rather than spinning forever.
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), size - copied));
                int read = in.read(buffer, copied);
                if (read <= 0) throw new IOException("Could not make progress copying history media");
                buffer.flip();
                while (buffer.hasRemaining()) {
                    int written = out.write(buffer);
                    if (written <= 0) throw new IOException("Could not make progress writing history media");
                }
                copied += read;
            }
            if (copied != size) throw new IOException("History media copy was incomplete");
            out.force(true);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        try {
            try {
                java.nio.file.Files.move(tmp.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                java.nio.file.Files.move(tmp.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Preserve the previous destination if publication fails.
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Could not finalize history media file", e);
        }
    }

    private static boolean deleteTree(File root) {
        if (root == null || !root.exists()) return true;
        boolean ok = true;
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) ok = deleteTree(child) && ok;
                else ok = (!child.exists() || child.delete()) && ok;
            }
        }
        return (!root.exists() || root.delete()) && ok;
    }
}
