package com.notcherry.lfm3pocket;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Copies one SAF stream into private storage. Selection belongs to the caller after load succeeds. */
public final class ModelImporter {
    public interface Source { InputStream open() throws IOException; }
    public interface Progress { void copied(long bytes); }
    private final File directory;

    public ModelImporter(File directory) { this.directory = directory; }

    public File importFile(Source source, long expectedBytes, AtomicBoolean cancelled,
                           Progress progress) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create model directory");
        String id = UUID.randomUUID().toString();
        File temporary = new File(directory, id + ".partial");
        File destination = new File(directory, id + ".gguf");
        try {
            checkCancelled(cancelled);
            long copied = 0;
            try (InputStream input = source.open(); FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IOException("Cannot read the selected document");
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    output.write(buffer, 0, count);
                    copied += count;
                    progress.copied(copied);
                }
                output.getFD().sync();
            }
            checkCancelled(cancelled);
            if (expectedBytes >= 0 && copied != expectedBytes) throw new IOException("Selected document was truncated or changed during import");
            if (copied < 1024 * 1024) throw new IOException("File is too small to be an LFM3 GGUF");
            try (RandomAccessFile check = new RandomAccessFile(temporary, "r")) {
                if (check.readInt() != 0x47475546) throw new IOException("Selected document is not GGUF");
                int version = Integer.reverseBytes(check.readInt());
                if (version != 2 && version != 3) throw new IOException("Unsupported GGUF version");
            }
            // Same-directory atomic publication never overwrites a loaded model.
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return destination;
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Import cancelled");
    }
}
