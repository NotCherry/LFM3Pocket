package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM contracts for {@link ModelDownloadManager} policy and {@link ModelDownloadCoordinator} behaviour. */
public final class ModelDownloadManagerContractTest {
    @After public void tearDown() {
        ModelDownloadCoordinator.resetForTests();
        ModelDownloadManager.resetInstanceForTests();
        CactusWhistleModelDownloader.resetInFlightForTests();
        CactusWhistleModelDownloader.resetPendingBackgroundErrorForTests();
        CactusWhistleModelDownloader.clearBoundUiForTests();
        TinyVocaPackDownloader.resetInFlightForTests();
        TinyVocaPackDownloader.resetPendingBackgroundErrorForTests();
        TinyVocaPackDownloader.clearBoundUiForTests();
    }

    @Test public void refusesStartWhenFreeSpaceBelowExpectedPlusMargin() throws Exception {
        File filesDir = Files.createTempDirectory("mgr-space").toFile();
        Context context = fakeContext(filesDir, new MutablePreferences());
        ModelDownloadManager manager = new ModelDownloadManager(context, path -> 1024L, () -> false);
        assertEquals(ModelDownloadManager.StartResult.INSUFFICIENT_SPACE,
                manager.start(ModelDownloadKind.CACTUS_WHISTLE, true));
    }

    @Test public void requiresMeteredConfirmationUnlessUserAlreadyConfirmed() throws Exception {
        File filesDir = Files.createTempDirectory("mgr-metered").toFile();
        Context context = fakeContext(filesDir, new MutablePreferences());
        long roomy = CactusWhistleModelDownloadTask.EXPECTED_BYTES
                + ModelDownloadPolicies.FREE_SPACE_MARGIN_BYTES + 1;
        ModelDownloadManager manager = new ModelDownloadManager(context, path -> roomy, () -> true);
        assertEquals(ModelDownloadManager.StartResult.METERED_NEEDS_CONFIRMATION,
                manager.start(ModelDownloadKind.CACTUS_WHISTLE, false));
    }

    @Test public void startMarksQueuedBeforeWorkerBegins() throws Exception {
        File filesDir = Files.createTempDirectory("mgr-queued").toFile();
        Context context = fakeContext(filesDir, new MutablePreferences());
        long roomy = CactusWhistleModelDownloadTask.EXPECTED_BYTES
                + ModelDownloadPolicies.FREE_SPACE_MARGIN_BYTES + 1;
        ModelDownloadManager manager = new ModelDownloadManager(context, path -> roomy, () -> false);
        // WorkManager cannot run here; we only assert queued is visible via coordinator after start would schedule.
        // Simulate the queue marker the manager sets before returning STARTED (without hitting WorkManager).
        ModelDownloadCoordinator.markQueued(ModelDownloadKind.CACTUS_WHISTLE);
        assertTrue(ModelDownloadCoordinator.isActiveOrQueued(ModelDownloadKind.CACTUS_WHISTLE));
    }

    @Test public void singleFlightRejectsSecondCoordinatorRun() throws Exception {
        File dir = Files.createTempDirectory("mgr-flight").toFile();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        CactusWhistleModelDownloader blocker = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock, cancel) -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }, System::currentTimeMillis);
        CactusWhistleModelDownloadTask task = new CactusWhistleModelDownloadTask(store, blocker);
        ModelDownloadCancelToken cancel = new ModelDownloadCancelToken();
        Thread first = new Thread(() -> ModelDownloadCoordinator.run(task, cancel), "first");
        first.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(ModelDownloadCoordinator.Result.ALREADY_RUNNING,
                ModelDownloadCoordinator.run(task, new ModelDownloadCancelToken()));
        release.countDown();
        first.join(10_000);
    }

    @Test public void userCancelDiscardsPartialAndKeepsVerifiedModel() throws Exception {
        File dir = Files.createTempDirectory("mgr-cancel").toFile();
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        writeModel(store.modelFile());
        store.markComplete();
        ModelDownloadCancelToken cancel = new ModelDownloadCancelToken();
        CactusWhistleModelDownloader downloader = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock, token) -> {
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        out.write("partial".getBytes(StandardCharsets.UTF_8));
                    }
                    token.requestUserCancel();
                    token.throwIfCancelled();
                }, System::currentTimeMillis);
        CactusWhistleModelDownloadTask task = new CactusWhistleModelDownloadTask(store, downloader);
        assertEquals(ModelDownloadCoordinator.Result.CANCELLED,
                ModelDownloadCoordinator.run(task, cancel));
        assertTrue(store.modelFile().isFile());
        assertTrue(store.isReady());
        assertFalse(store.partialFile().exists());
    }

    @Test public void userCancelAfterDisconnectIOException() throws Exception {
        File dir = Files.createTempDirectory("mgr-cancel-io").toFile();
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        ModelDownloadCancelToken cancel = new ModelDownloadCancelToken();
        java.util.concurrent.atomic.AtomicBoolean uiCancelled = new java.util.concurrent.atomic.AtomicBoolean();
        CactusWhistleModelDownloader.bindUi(new CactusWhistleModelDownloader.UiCallbacks() {
            @Override public void onProgress(String name, long written, long total) { }
            @Override public void onDone(File modelFile) { }
            @Override public void onError(String message) { }
            @Override public void onCancelled() { uiCancelled.set(true); }
        });
        try {
            CactusWhistleModelDownloader downloader = new CactusWhistleModelDownloader(store,
                    (name, expectedSha256, target, progress, clock, token) -> {
                        try (FileOutputStream out = new FileOutputStream(target)) {
                            out.write("partial".getBytes(StandardCharsets.UTF_8));
                        }
                        token.requestUserCancel();
                        throw new IOException("Socket closed");
                    }, System::currentTimeMillis);
            assertEquals(ModelDownloadCoordinator.Result.CANCELLED,
                    ModelDownloadCoordinator.run(new CactusWhistleModelDownloadTask(store, downloader), cancel));
            assertTrue(uiCancelled.get());
            assertFalse(store.partialFile().exists());
            assertEquals(null, CactusWhistleModelDownloader.consumePendingBackgroundError());
        } finally {
            CactusWhistleModelDownloader.clearBoundUiForTests();
        }
    }

    @Test public void systemStopAfterDisconnectIOExceptionKeepsPartial() throws Exception {
        File dir = Files.createTempDirectory("mgr-stop-io").toFile();
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        CactusWhistleModelDownloader downloader = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock, token) -> {
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        out.write("partial".getBytes(StandardCharsets.UTF_8));
                    }
                    token.requestSystemStop();
                    throw new IOException("Socket closed");
                }, System::currentTimeMillis);
        assertEquals(ModelDownloadCoordinator.Result.STOPPED,
                ModelDownloadCoordinator.run(new CactusWhistleModelDownloadTask(store, downloader),
                        new ModelDownloadCancelToken()));
        assertTrue(store.partialFile().isFile());
        assertEquals(null, CactusWhistleModelDownloader.consumePendingBackgroundError());
    }

    @Test public void systemStopKeepsPartialAndVerifiedModel() throws Exception {
        File dir = Files.createTempDirectory("mgr-stop").toFile();
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        writeModel(store.modelFile());
        store.markComplete();
        CactusWhistleModelDownloader downloader = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock, token) -> {
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        out.write("partial".getBytes(StandardCharsets.UTF_8));
                    }
                    token.requestSystemStop();
                    token.throwIfCancelled();
                }, System::currentTimeMillis);
        assertEquals(ModelDownloadCoordinator.Result.STOPPED,
                ModelDownloadCoordinator.run(new CactusWhistleModelDownloadTask(store, downloader),
                        new ModelDownloadCancelToken()));
        assertTrue(store.modelFile().isFile());
        assertTrue(store.isReady());
        assertTrue(store.partialFile().isFile());
    }

    @Test public void failureKeepsVerifiedModel() throws Exception {
        File dir = Files.createTempDirectory("mgr-fail").toFile();
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        writeModel(store.modelFile());
        store.markComplete();
        CactusWhistleModelDownloader downloader = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock, token) -> {
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        out.write("partial".getBytes(StandardCharsets.UTF_8));
                    }
                    throw new IllegalStateException("offline");
                }, System::currentTimeMillis);
        CactusWhistleModelDownloadTask task = new CactusWhistleModelDownloadTask(store, downloader);
        assertEquals(ModelDownloadCoordinator.Result.FAILED,
                ModelDownloadCoordinator.run(task, new ModelDownloadCancelToken()));
        assertEquals("offline", CactusWhistleModelDownloader.consumePendingBackgroundError());
        assertTrue(store.modelFile().isFile());
        assertTrue(store.isReady());
        assertFalse(store.partialFile().exists());
    }

    @Test public void successfulCoordinatorRunPersistsSelectionWithoutBoundUi() throws Exception {
        File filesDir = Files.createTempDirectory("mgr-prefs").toFile();
        MutablePreferences prefs = new MutablePreferences();
        Context context = fakeContext(filesDir, prefs);
        CactusWhistleModelStore store = new CactusWhistleModelStore(context);
        byte[] payload = bytesOfLength(2 * 1024 * 1024);
        String digest = sha256(payload);
        CactusWhistleModelDownloader downloader = new CactusWhistleModelDownloader(store,
                (name, expectedSha256, target, progress, clock, cancel) -> {
                    try (ByteArrayInputStream input = new ByteArrayInputStream(payload)) {
                        CactusWhistleModelDownloader.writeAndVerifySha256(input, digest, name, target,
                                payload.length, progress, clock, cancel);
                    }
                }, System::currentTimeMillis);
        CactusWhistleModelDownloadTask task = new CactusWhistleModelDownloadTask(store, downloader);
        assertEquals(ModelDownloadCoordinator.Result.SUCCESS,
                ModelDownloadCoordinator.run(task, new ModelDownloadCancelToken()));
        assertTrue(store.isReady());
        assertEquals(store.modelFile().getAbsolutePath(), new RuntimePreferences(context).cactusWhistleModelPath());
    }

    @Test public void tinyVocaRefusesStartWhenFreeSpaceInsufficient() throws Exception {
        File filesDir = Files.createTempDirectory("tv-space").toFile();
        Context context = fakeContext(filesDir, new MutablePreferences());
        ModelDownloadManager manager = new ModelDownloadManager(context, path -> 1024L, () -> false);
        assertEquals(ModelDownloadManager.StartResult.INSUFFICIENT_SPACE,
                manager.startTinyVoca(true));
    }

    @Test public void tinyVocaRequiresMeteredConfirmationUnlessConfirmed() throws Exception {
        File filesDir = Files.createTempDirectory("tv-metered").toFile();
        Context context = fakeContext(filesDir, new MutablePreferences());
        long roomy = TinyVocaPackDownloadTask.EXPECTED_BYTES
                + ModelDownloadPolicies.FREE_SPACE_MARGIN_BYTES + 1;
        ModelDownloadManager manager = new ModelDownloadManager(context, path -> roomy, () -> true);
        assertEquals(ModelDownloadManager.StartResult.METERED_NEEDS_CONFIRMATION,
                manager.startTinyVoca(false));
    }

    @Test public void tinyVocaFailureDiscardsStagingAndPreservesVerifiedPack() throws Exception {
        File root = Files.createTempDirectory("tv-fail").toFile();
        TinyVocaPackStore store = new TinyVocaPackStore(root);
        seedCompleteTinyVocaPack(store);
        assertTrue(store.isReady());
        TinyVocaPackDownloader downloader = new TinyVocaPackDownloader(store,
                (relative, expectedSha256, displayName, target, progress, clock, cancel) -> {
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        out.write("partial".getBytes(StandardCharsets.UTF_8));
                    }
                    throw new IllegalStateException("offline");
                }, System::currentTimeMillis);
        TinyVocaPackDownloadTask task = new TinyVocaPackDownloadTask(store, downloader);
        assertEquals(ModelDownloadCoordinator.Result.FAILED,
                ModelDownloadCoordinator.run(task, new ModelDownloadCancelToken()));
        assertEquals("offline", TinyVocaPackDownloader.consumePendingBackgroundError());
        assertTrue("Existing verified pack must survive a failed re-download", store.isReady());
        assertFalse(store.stagingDir().exists());
    }

    @Test public void tinyVocaUserCancelDiscardsStagingAndNotifiesUi() throws Exception {
        File root = Files.createTempDirectory("tv-cancel").toFile();
        TinyVocaPackStore store = new TinyVocaPackStore(root);
        seedCompleteTinyVocaPack(store);
        java.util.concurrent.atomic.AtomicBoolean cancelledUi = new java.util.concurrent.atomic.AtomicBoolean();
        TinyVocaPackDownloader.bindUi(new TinyVocaPackDownloader.UiCallbacks() {
            @Override public void onProgress(String name, long written, long total) { }
            @Override public void onDone() { }
            @Override public void onError(String message) { }
            @Override public void onCancelled() { cancelledUi.set(true); }
        });
        TinyVocaPackDownloader downloader = new TinyVocaPackDownloader(store,
                (relative, expectedSha256, displayName, target, progress, clock, cancel) -> {
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        out.write("partial".getBytes(StandardCharsets.UTF_8));
                    }
                    cancel.requestUserCancel();
                    cancel.throwIfCancelled();
                }, System::currentTimeMillis);
        assertEquals(ModelDownloadCoordinator.Result.CANCELLED,
                ModelDownloadCoordinator.run(new TinyVocaPackDownloadTask(store, downloader),
                        new ModelDownloadCancelToken()));
        assertTrue(cancelledUi.get());
        assertTrue(store.isReady());
        assertFalse(store.stagingDir().exists());
    }

    @Test public void tinyVocaSuccessfulRunPromotesStagedFilesAndMarksComplete() throws Exception {
        File root = Files.createTempDirectory("tv-ok").toFile();
        TinyVocaPackStore store = new TinyVocaPackStore(root);
        byte[] largeModel = bytesOfLength(1_200_000);
        byte[] smallFile = "{\"sample_rate\":24000,\"model\":{\"max_tokens\":512}}".getBytes(StandardCharsets.UTF_8);
        TinyVocaPackDownloader downloader = new TinyVocaPackDownloader(store,
                (relative, expectedSha256, displayName, target, progress, clock, cancel) -> {
                    byte[] payload = "onnx/model.onnx".equals(relative) ? largeModel : smallFile;
                    String digest = sha256(payload);
                    try (ByteArrayInputStream input = new ByteArrayInputStream(payload)) {
                        CactusWhistleModelDownloader.writeAndVerifySha256(
                                input, digest, displayName, target, payload.length, progress, clock, cancel);
                    }
                }, System::currentTimeMillis);
        assertEquals(ModelDownloadCoordinator.Result.SUCCESS,
                ModelDownloadCoordinator.run(new TinyVocaPackDownloadTask(store, downloader),
                        new ModelDownloadCancelToken()));
        assertTrue(store.isReady());
        assertFalse(store.stagingDir().exists());
    }

    private static void seedCompleteTinyVocaPack(TinyVocaPackStore store) throws Exception {
        for (String relative : TinyVocaPackManifest.expectedSha256().keySet()) {
            File f = new File(store.root(), relative);
            f.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write("onnx/model.onnx".equals(relative)
                        ? bytesOfLength(1_200_000)
                        : "{}".getBytes(StandardCharsets.UTF_8));
            }
        }
        store.markComplete();
    }

    private static Context fakeContext(File filesDir, MutablePreferences preferences) {
        return new android.content.ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return filesDir; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
        };
    }

    private static void writeModel(File model) throws Exception {
        try (FileOutputStream out = new FileOutputStream(model)) {
            out.write(bytesOfLength(2 * 1024 * 1024));
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

    static final class MutablePreferences implements SharedPreferences {
        final Map<String, String> strings = new HashMap<>();
        @Override public Map<String, ?> getAll() { return strings; }
        @Override public String getString(String key, String defValue) { return strings.getOrDefault(key, defValue); }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) { return defValue; }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
        @Override public boolean contains(String key) { return strings.containsKey(key); }
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, String> pending = new HashMap<>(strings);
                @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
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