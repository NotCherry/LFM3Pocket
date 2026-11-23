package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CactusWhistleModelDownloaderContractTest {
    @After public void tearDown() {
        CactusWhistleModelDownloader.resetInFlightForTests();
        CactusWhistleModelDownloader.resetPendingBackgroundErrorForTests();
        CactusWhistleModelDownloader.clearBoundUiForTests();
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
        AtomicBoolean errored = new AtomicBoolean();
        AtomicReference<String> errorMessage = new AtomicReference<>();
        assertTrue(downloader.download(new CactusWhistleModelDownloader.Progress() {
            @Override public void onFile(String name, long written, long total) { }
            @Override public void onDone(File modelFile) { fail("expected sha failure"); }
            @Override public void onError(String message) {
                errored.set(true);
                errorMessage.set(message);
            }
        }));
        assertTrue(errored.get());
        assertTrue(errorMessage.get().contains("SHA-256 mismatch"));
        assertTrue(store.modelFile().isFile());
        assertTrue(store.isReady());
        assertFalse(store.partialFile().exists());
    }

    @Test public void successfulUpdateReplacesPreviousVerifiedModel() throws Exception {
        File dir = Files.createTempDirectory("whistle-replace").toFile();
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        writeModel(store.modelFile(), (byte) 1);
        store.markComplete();
        long previousLength = store.modelFile().length();
        byte[] nextPayload = bytesOfLength(2 * 1024 * 1024 + 32);
        String nextDigest = sha256(nextPayload);
        CactusWhistleModelDownloader downloader = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock) -> {
                    try (ByteArrayInputStream input = new ByteArrayInputStream(nextPayload)) {
                        CactusWhistleModelDownloader.writeAndVerifySha256(input, nextDigest, name, target,
                                nextPayload.length, progress, clock);
                    }
                }, System::currentTimeMillis);
        assertTrue(downloader.download(null));
        assertNotEquals(previousLength, store.modelFile().length());
        assertEquals(nextPayload.length, store.modelFile().length());
        assertTrue(store.isReady());
        assertEquals(CactusWhistleModelManifest.HF_REVISION, store.storedRevision());
    }

    @Test public void downloadWithoutBoundUiStillSelectsModelInPreferences() throws Exception {
        File filesDir = Files.createTempDirectory("whistle-prefs").toFile();
        MutablePreferences prefs = new MutablePreferences();
        Context context = new FakeFilesContext(filesDir, prefs);
        CactusWhistleModelStore store = new CactusWhistleModelStore(context);
        byte[] payload = bytesOfLength(2 * 1024 * 1024);
        String digest = sha256(payload);
        CactusWhistleModelDownloader downloader = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock) -> {
                    try (ByteArrayInputStream input = new ByteArrayInputStream(payload)) {
                        CactusWhistleModelDownloader.writeAndVerifySha256(input, digest, name, target,
                                payload.length, progress, clock);
                    }
                }, System::currentTimeMillis);
        assertTrue(downloader.download(null));
        assertTrue(store.isReady());
        assertEquals(store.modelFile().getAbsolutePath(),
                new RuntimePreferences(context).cactusWhistleModelPath());
        assertNull(CactusWhistleModelDownloader.consumePendingBackgroundError());
    }

    @Test public void failureWithoutBoundUiRetainsErrorForNextResume() throws Exception {
        File dir = Files.createTempDirectory("whistle-bg-err").toFile();
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        CactusWhistleModelDownloader downloader = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock) ->
                        { throw new IllegalStateException("offline"); }, System::currentTimeMillis);
        assertTrue(downloader.download(null));
        assertEquals("offline", CactusWhistleModelDownloader.consumePendingBackgroundError());
        assertNull(CactusWhistleModelDownloader.consumePendingBackgroundError());
    }

    @Test public void formatProgressLabelShowsPercentOrBytes() {
        assertEquals("whistle.cact · 50%", CactusWhistleModelDownloader.formatProgressLabel("whistle.cact", 5, 10));
        assertEquals("whistle.cact · 4096 B", CactusWhistleModelDownloader.formatProgressLabel("whistle.cact", 4096, 0));
    }

    private static void writeModel(File model) throws Exception {
        writeModel(model, (byte) 0);
    }

    private static void writeModel(File model, byte fill) throws Exception {
        byte[] bytes = bytesOfLength(2 * 1024 * 1024);
        for (int i = 0; i < bytes.length; i++) bytes[i] = fill;
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

    private static final class FakeFilesContext extends android.content.ContextWrapper {
        private final File filesDir;
        private final MutablePreferences preferences;
        FakeFilesContext(File filesDir, MutablePreferences preferences) {
            super(null);
            this.filesDir = filesDir;
            this.preferences = preferences;
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return filesDir; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
    }

    private static final class MutablePreferences implements SharedPreferences {
        private final Map<String, String> strings = new HashMap<>();
        @Override public Map<String, ?> getAll() { return strings; }
        @Override public String getString(String key, String defValue) {
            return strings.getOrDefault(key, defValue);
        }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) { return defValue; }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
        @Override public boolean contains(String key) { return strings.containsKey(key); }
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, String> pending = new HashMap<>(strings);
                @Override public Editor putString(String key, String value) {
                    pending.put(key, value);
                    return this;
                }
                @Override public Editor putStringSet(String key, Set<String> values) { return this; }
                @Override public Editor putInt(String key, int value) { return this; }
                @Override public Editor putLong(String key, long value) { return this; }
                @Override public Editor putFloat(String key, float value) { return this; }
                @Override public Editor putBoolean(String key, boolean value) { return this; }
                @Override public Editor remove(String key) { pending.remove(key); return this; }
                @Override public Editor clear() { pending.clear(); return this; }
                @Override public boolean commit() { strings.clear(); strings.putAll(pending); return true; }
                @Override public void apply() { commit(); }
            };
        }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    }
}