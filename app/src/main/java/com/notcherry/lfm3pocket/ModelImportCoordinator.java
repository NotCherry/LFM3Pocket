package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coordinates GGUF private-storage import, external SAF URI loading, Cactus Whistle `.cact`
 * import, and model reload/unload workflows outside {@link MainActivity} (#322).
 */
final class ModelImportCoordinator {
    static final class Deps {
        final Activity activity;
        final ModelRuntime runtime;
        final ExecutorService worker;
        final Supplier<SharedPreferences> preferences;
        final BooleanSupplier isDestroyed;
        final BooleanSupplier isBusy;
        final Consumer<Boolean> setBusy;
        final Consumer<CharSequence> setChatStatus;
        final Runnable updateControls;
        final Runnable onModelOperationFinished;
        final Runnable onWhistleImported;

        Deps(Activity activity, ModelRuntime runtime, ExecutorService worker,
                Supplier<SharedPreferences> preferences, BooleanSupplier isDestroyed,
                BooleanSupplier isBusy, Consumer<Boolean> setBusy,
                Consumer<CharSequence> setChatStatus, Runnable updateControls,
                Runnable onModelOperationFinished, Runnable onWhistleImported) {
            this.activity = activity;
            this.runtime = runtime;
            this.worker = worker;
            this.preferences = preferences;
            this.isDestroyed = isDestroyed;
            this.isBusy = isBusy;
            this.setBusy = setBusy;
            this.setChatStatus = setChatStatus;
            this.updateControls = updateControls;
            this.onModelOperationFinished = onModelOperationFinished;
            this.onWhistleImported = onWhistleImported;
        }
    }

    private final Deps deps;
    private volatile AtomicBoolean importCancellation;

    ModelImportCoordinator(Deps deps) {
        this.deps = deps;
    }

    static boolean isContentUri(String value) {
        return value != null && value.startsWith("content:");
    }

    boolean isImporting() {
        return importCancellation != null;
    }

    boolean cancelImportIfActive() {
        AtomicBoolean active = importCancellation;
        if (active == null) return false;
        active.set(true);
        deps.setChatStatus.accept("Cancelling import...");
        return true;
    }

    void cancelOnDestroy() {
        AtomicBoolean active = importCancellation;
        if (active != null) active.set(true);
    }

    void reloadSelectedModel() {
        if (deps.isBusy.getAsBoolean()) return;
        SharedPreferences prefs = deps.preferences.get();
        String model = prefs.getString("model", "");
        if (!model.isEmpty()) loadSelectedModel(model, prefs.getString("mmproj", ""));
    }

    void unloadSelectedModel() {
        if (deps.isBusy.getAsBoolean() || deps.runtime.handle() == 0L) return;
        deps.setBusy.accept(true);
        deps.setChatStatus.accept("Unloading model...");
        deps.updateControls.run();
        deps.runtime.unload("user", () -> {
            if (deps.isDestroyed.getAsBoolean()) return;
            deps.setBusy.accept(false);
            deps.setChatStatus.accept("Model unloaded. Selected files remain available for reload.");
            deps.updateControls.run();
        });
    }

    void importPickedGguf(Uri uri, boolean isProjector) {
        deps.setBusy.accept(true);
        deps.updateControls.run();
        deps.setChatStatus.accept(isProjector
                ? "Importing projector into private storage..."
                : deps.activity.getString(R.string.importing_model));
        AtomicBoolean cancellation = new AtomicBoolean();
        importCancellation = cancellation;
        deps.updateControls.run();
        deps.worker.execute(() -> {
            File imported = null;
            try {
                SharedPreferences prefs = deps.preferences.get();
                String previousModel = prefs.getString("model", "");
                String previousProjector = prefs.getString("mmproj", "");
                if (isProjector && previousModel.isEmpty()) {
                    throw new IOException("Import a model before its multimodal projector");
                }
                long expected = -1;
                String importedName = "Imported GGUF";
                try (Cursor cursor = deps.activity.getContentResolver().query(uri,
                        new String[] { OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME },
                        null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        if (!cursor.isNull(0)) expected = cursor.getLong(0);
                        if (!cursor.isNull(1)) importedName = cursor.getString(1);
                    }
                }
                imported = new ModelImporter(new File(deps.activity.getFilesDir(), "models")).importFile(
                        () -> deps.activity.getContentResolver().openInputStream(uri), expected, cancellation,
                        bytes -> deps.activity.runOnUiThread(() -> {
                            if (!deps.isDestroyed.getAsBoolean() && !cancellation.get()) {
                                deps.setChatStatus.accept("Importing GGUF: " + (bytes / 1024 / 1024)
                                        + " MiB. Stop to cancel.");
                            }
                        }));
                if (cancellation.get()) throw new IOException("Import cancelled");
                String model = isProjector ? previousModel : imported.getAbsolutePath();
                String projector = isProjector ? imported.getAbsolutePath() : "";
                if (deps.runtime.loadBlocking(model, projector, statusText -> deps.activity.runOnUiThread(() -> {
                    if (!deps.isDestroyed.getAsBoolean()) deps.setChatStatus.accept(statusText);
                }))) {
                    prefs.edit().putString("model", model)
                            .putString("mmproj", projector)
                            .putString(isProjector ? "projector_name" : "model_name", importedName).apply();
                    if (!isProjector) prefs.edit().remove("projector_name").apply();
                    // Only delete owned obsolete imports after the new selection loads.
                    deleteImportedModel(isProjector ? previousProjector : previousModel);
                    if (!isProjector) deleteImportedModel(previousProjector);
                    imported = null;
                }
            } catch (Throwable e) {
                final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                deps.activity.runOnUiThread(() -> {
                    if (!deps.isDestroyed.getAsBoolean()) {
                        deps.setChatStatus.accept("Import failed: " + message);
                    }
                });
            } finally {
                if (imported != null) imported.delete();
                importCancellation = null;
                deps.activity.runOnUiThread(() -> {
                    if (!deps.isDestroyed.getAsBoolean()) {
                        deps.setBusy.accept(false);
                        deps.updateControls.run();
                        deps.onModelOperationFinished.run();
                    }
                });
            }
        });
    }

    void openExternalDocument(Uri uri, boolean projector) {
        deps.setBusy.accept(true);
        deps.updateControls.run();
        deps.setChatStatus.accept(deps.activity.getString(R.string.opening_external_model));
        deps.worker.execute(() -> {
            ExternalModelStore store = ((Lfm3PocketApplication) deps.activity.getApplication()).externalModels();
            try {
                SharedPreferences prefs = deps.preferences.get();
                String previousModel = prefs.getString("model", "");
                String previousProjector = prefs.getString("mmproj", "");
                if (projector && previousModel.isEmpty()) {
                    throw new IOException("Open or import a model before its multimodal projector");
                }
                String displayName = "GGUF";
                try (Cursor cursor = deps.activity.getContentResolver().query(uri,
                        new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                        displayName = cursor.getString(0);
                    }
                }
                HashSet<String> protectedUris = new HashSet<>();
                if (isContentUri(previousModel)) protectedUris.add(previousModel);
                if (isContentUri(previousProjector)) protectedUris.add(previousProjector);
                store.remember(uri, displayName, projector, protectedUris);
                ExternalModelStore.Opened opened = store.open(uri);
                String modelPath;
                String projectorPath;
                String nextModelUri = projector ? previousModel : uri.toString();
                String nextProjectorUri = projector ? uri.toString() : "";
                if (projector) {
                    modelPath = resolveLoadPath(previousModel, store);
                    projectorPath = opened.path;
                } else {
                    modelPath = opened.path;
                    projectorPath = "";
                }
                GgufSelection.validate(new File(modelPath),
                        projectorPath.isEmpty() ? null : new File(projectorPath));
                boolean loaded = deps.runtime.loadBlocking(modelPath, projectorPath,
                        statusText -> deps.activity.runOnUiThread(() -> {
                            if (!deps.isDestroyed.getAsBoolean()) deps.setChatStatus.accept(statusText);
                        }));
                if (loaded) {
                    prefs.edit()
                            .putString("model", nextModelUri)
                            .putString("mmproj", nextProjectorUri)
                            .putString(projector ? "projector_name" : "model_name", displayName)
                            .apply();
                    if (!projector) prefs.edit().remove("projector_name").apply();
                    HashSet<String> active = new HashSet<>();
                    if (isContentUri(nextModelUri)) active.add(nextModelUri);
                    if (isContentUri(nextProjectorUri)) active.add(nextProjectorUri);
                    store.releaseExcept(active);
                } else {
                    HashSet<String> active = new HashSet<>();
                    if (isContentUri(previousModel)) active.add(previousModel);
                    if (isContentUri(previousProjector)) active.add(previousProjector);
                    store.releaseExcept(active);
                }
            } catch (Throwable e) {
                final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                deps.activity.runOnUiThread(() -> {
                    if (!deps.isDestroyed.getAsBoolean()) {
                        deps.setChatStatus.accept("Could not open storage model: " + message);
                    }
                });
            } finally {
                deps.activity.runOnUiThread(() -> {
                    if (!deps.isDestroyed.getAsBoolean()) {
                        deps.setBusy.accept(false);
                        deps.updateControls.run();
                        deps.onModelOperationFinished.run();
                    }
                });
            }
        });
    }

    void importWhistleModel(Uri uri) {
        deps.setBusy.accept(true);
        deps.updateControls.run();
        deps.setChatStatus.accept(deps.activity.getString(R.string.opening_external_model));
        deps.worker.execute(() -> {
            File dest = null;
            try {
                String name = "whistle.cact";
                try (Cursor cursor = deps.activity.getContentResolver().query(uri,
                        new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                        String display = cursor.getString(0);
                        if (display != null && !display.trim().isEmpty()) {
                            name = new File(display.trim()).getName();
                        }
                    }
                } catch (Exception ignored) { }
                File dir = new File(deps.activity.getFilesDir(), "cactus");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("Cannot create Cactus model directory");
                }
                dest = new File(dir, name);
                File temp = new File(dir, name + ".partial");
                try (InputStream in = deps.activity.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    if (in == null) throw new IOException("Cannot read the selected document");
                    byte[] buffer = new byte[1024 * 64];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    out.getFD().sync();
                }
                Files.move(temp.toPath(), dest.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                File saved = dest;
                deps.activity.runOnUiThread(() -> {
                    if (deps.isDestroyed.getAsBoolean()) return;
                    CactusWhistleBridge.resetLoadedModel();
                    new RuntimePreferences(deps.activity).setCactusWhistleModelPath(saved.getAbsolutePath());
                    if (deps.runtime != null) deps.runtime.refreshVoiceAvailability();
                    deps.onWhistleImported.run();
                    deps.setBusy.accept(false);
                    deps.setChatStatus.accept(saved.getName() + " selected for Cactus Whistle STT.");
                    deps.updateControls.run();
                });
            } catch (Throwable e) {
                if (dest != null) dest.delete();
                final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                deps.activity.runOnUiThread(() -> {
                    if (deps.isDestroyed.getAsBoolean()) return;
                    deps.setBusy.accept(false);
                    deps.setChatStatus.accept("Could not import Whistle model: " + message);
                    deps.updateControls.run();
                });
            }
        });
    }

    void loadSelectedModel(String model, String projector) {
        if (isContentUri(model) || isContentUri(projector)) {
            final String targetModel = model;
            final String targetProjector = projector == null ? "" : projector;
            deps.setBusy.accept(true);
            deps.updateControls.run();
            deps.setChatStatus.accept(deps.activity.getString(R.string.opening_external_model));
            deps.worker.execute(() -> {
                try {
                    ExternalModelStore store = ((Lfm3PocketApplication) deps.activity.getApplication())
                            .externalModels();
                    String modelPath = resolveLoadPath(targetModel, store);
                    String projectorPath = targetProjector.isEmpty()
                            ? "" : resolveLoadPath(targetProjector, store);
                    boolean ok = deps.runtime.loadBlocking(modelPath, projectorPath,
                            statusText -> deps.activity.runOnUiThread(() -> {
                                if (!deps.isDestroyed.getAsBoolean()) deps.setChatStatus.accept(statusText);
                            }));
                    if (ok) {
                        HashSet<String> active = new HashSet<>();
                        if (isContentUri(targetModel)) active.add(targetModel);
                        if (isContentUri(targetProjector)) active.add(targetProjector);
                        store.releaseExcept(active);
                    } else {
                        releaseStaleExternalLeases(store, targetModel, targetProjector);
                        if (!deps.isDestroyed.getAsBoolean()) {
                            deps.activity.runOnUiThread(() ->
                                    deps.setChatStatus.accept("Could not load the selected storage model."));
                        }
                    }
                } catch (Throwable e) {
                    ExternalModelStore store = ((Lfm3PocketApplication) deps.activity.getApplication())
                            .externalModels();
                    releaseStaleExternalLeases(store, targetModel, targetProjector);
                    final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                    deps.activity.runOnUiThread(() -> {
                        if (!deps.isDestroyed.getAsBoolean()) {
                            deps.setChatStatus.accept("Could not open storage model: " + message);
                        }
                    });
                } finally {
                    deps.activity.runOnUiThread(() -> {
                        if (!deps.isDestroyed.getAsBoolean()) {
                            deps.setBusy.accept(false);
                            deps.updateControls.run();
                            deps.onModelOperationFinished.run();
                        }
                    });
                }
            });
            return;
        }
        loadModelInBackground(model, projector);
    }

    private void loadModelInBackground(String path, String projector) {
        deps.setBusy.accept(true);
        deps.updateControls.run();
        deps.setChatStatus.accept(deps.activity.getString(R.string.loading_model));
        deps.runtime.load(path, projector, statusText -> {
            if (deps.isDestroyed.getAsBoolean()) return;
            deps.setBusy.accept(false);
            deps.setChatStatus.accept(statusText);
            deps.updateControls.run();
            deps.onModelOperationFinished.run();
        });
    }

    private void releaseStaleExternalLeases(ExternalModelStore store, String targetModel, String targetProjector) {
        SharedPreferences prefs = deps.preferences.get();
        String currentModel = prefs.getString("model", "");
        String currentProjector = prefs.getString("mmproj", "");
        HashSet<String> active = new HashSet<>();
        if (isContentUri(currentModel)) active.add(currentModel);
        if (isContentUri(currentProjector)) active.add(currentProjector);
        // Keep current model usable; release only candidate descriptors not needed by it.
        if (isContentUri(targetModel) && !active.contains(targetModel)) store.release(targetModel);
        if (isContentUri(targetProjector) && !active.contains(targetProjector)) store.release(targetProjector);
        // Defensive: do not retain unrelated stale leases beyond current selection.
        store.releaseExcept(active);
    }

    private String resolveLoadPath(String stored, ExternalModelStore store) throws IOException {
        if (isContentUri(stored)) {
            return store.open(Uri.parse(stored)).path;
        }
        if (stored == null || stored.isEmpty() || !new File(stored).isFile()) {
            throw new IOException("Selected model is missing");
        }
        return stored;
    }

    private void deleteImportedModel(String path) {
        if (path == null || isContentUri(path)) return;
        File file = new File(path);
        if (new File(deps.activity.getFilesDir(), "models").equals(file.getParentFile())) file.delete();
    }
}
