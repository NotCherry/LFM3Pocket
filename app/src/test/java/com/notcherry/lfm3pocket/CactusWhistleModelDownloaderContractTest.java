package com.notcherry.lfm3pocket;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CactusWhistleModelDownloaderContractTest {
    @After public void tearDown() {
        CactusWhistleModelDownloader.resetInFlightForTests();
    }

    @Test public void throttlesProgressAndEmitsFinalUpdate() throws Exception {
        File dir = Files.createTempDirectory("whistle-dl").toFile();
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        byte[] payload = bytesOfLength(64 * 1024);
        String digest = sha256(payload);
        AtomicLong now = new AtomicLong();
        List<Long> writtenSnapshots = new ArrayList<>();
        CactusWhistleModelDownloader downloader = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock) -> {
                    try (ByteArrayInputStream input = new ByteArrayInputStream(payload)) {
                        CactusWhistleModelDownloader.writeAndVerifySha256(input, digest, name, target,
                                payload.length, progress == null ? null : new CactusWhistleModelDownloader.Progress() {
                                    @Override public void onFile(String n, long written, long total) {
                                        writtenSnapshots.add(written);
                                    }
                                    @Override public void onDone(File modelFile) { }
                                    @Override public void onError(String message) { }
                                }, clock);
                    }
                }, () -> now.addAndGet(50));
        assertTrue(downloader.download(null));
        assertTrue(writtenSnapshots.size() < payload.length / 8192);
        assertEquals(payload.length, (long) writtenSnapshots.get(writtenSnapshots.size() - 1));
    }

    @Test public void rejectsSecondDownloadWhileInFlight() throws Exception {
        File dir = Files.createTempDirectory("whistle-flight").toFile();
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CactusWhistleModelDownloader blocker = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock) -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }, System::currentTimeMillis);
        AtomicBoolean started = new AtomicBoolean();
        Thread first = new Thread(() -> started.set(blocker.download(null)), "first");
        first.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(CactusWhistleModelDownloader.isDownloading());
        CactusWhistleModelDownloader second = new CactusWhistleModelDownloader(store);
        assertFalse(second.download(null));
        release.countDown();
        first.join(10_000);
        assertTrue(started.get());
    }

    @Test public void failedUpdateKeepsVerifiedModelAndClearsOnlyPartial() throws Exception {
        File dir = Files.createTempDirectory("whistle-fail").toFile();
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        writeModel(store.modelFile());
        store.markComplete();
        assertTrue(store.isReady());
        File revision = new File(dir, ".whistle-download-revision");
        assertTrue(revision.isFile());
        CactusWhistleModelDownloader downloader = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock) -> {
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        out.write("partial".getBytes(StandardCharsets.UTF_8));
                    }
                    throw new IllegalStateException("network drop");
                }, System::currentTimeMillis);
        AtomicBoolean errored = new AtomicBoolean();
        assertTrue(downloader.download(new CactusWhistleModelDownloader.Progress() {
            @Override public void onFile(String name, long written, long total) { }
            @Override public void onDone(File modelFile) { fail("expected error"); }
            @Override public void onError(String message) { errored.set(true); }
        }));
        assertTrue(errored.get());
        assertTrue(store.modelFile().isFile());
        assertTrue(store.isReady());
        assertFalse(store.partialFile().exists());
    }

    @Test public void shaMismatchDeletesPartialWithoutTouchingVerifiedModel() throws Exception {
        File dir = Files.createTempDirectory("whistle-sha").toFile();
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        writeModel(store.modelFile());
        store.markComplete();
        byte[] bad = "not-the-model".getBytes(StandardCharsets.UTF_8);
        CactusWhistleModelDownloader downloader = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock) -> {
                    try (ByteArrayInputStream input = new ByteArrayInputStream(bad)) {
                        CactusWhistleModelDownloader.writeAndVerifySha256(input,
                                CactusWhistleModelManifest.SHA256, name, target, bad.length, progress, clock);
                    }
                }, System::currentTimeMillis);
        assertTrue(downloader.download(new CactusWhistleModelDownloader.Progress() {
            @Override public void onFile(String name, long written, long total) { }
            @Override public void onDone(File modelFile) { fail("expected sha failure"); }
            @Override public void onError(String message) {
                assertTrue(message.contains("SHA-256 mismatch"));
            }
        }));
        assertTrue(store.modelFile().isFile());
        assertTrue(store.isReady());
        assertFalse(store.partialFile().exists());
    }

    @Test public void formatProgressLabelShowsPercentOrBytes() {
        assertEquals("whistle.cact · 50%", CactusWhistleModelDownloader.formatProgressLabel("whistle.cact", 5, 10));
        assertEquals("whistle.cact · 4096 B", CactusWhistleModelDownloader.formatProgressLabel("whistle.cact", 4096, 0));
    }

    private static void writeModel(File model) throws Exception {
        byte[] bytes = bytesOfLength(2 * 1024 * 1024);
        try (FileOutputStream out = new FileOutputStream(model)) {
            out.write(bytes);
        }
    }

    private static byte[] bytesOfLength(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i & 0xff);
        return bytes;
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(String.format(Locale.US, "%02x", value));
        }
        return hex.toString();
    }
}