package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coordinates photo, camera, audio, and document attachment import, preprocessing,
 * pending-attachment state, and cleanup outside {@link MainActivity} (#322).
 *
 * <p>Runs on its own non-inference I/O worker so slow SAF streams, image preprocessing, or
 * document parsing can never block {@link ModelRuntime#worker()} inference or Cactus STT sends,
 * and supports Stop/Clear cancellation with guaranteed busy-state recovery on any {@link Throwable}.
 */
final class AttachmentImportCoordinator {
    static final class Deps {
        final Activity activity;
        final ExecutorService worker;
        final Supplier<ChatPanel> chatPanel;
        final Supplier<ImageSettings> imageSettings;
        final BooleanSupplier isDestroyed;
        final Runnable recomputeBusy;
        final Consumer<CharSequence> setChatStatus;
        final Runnable updateControls;
        final ProcessingTelemetry telemetry;

        Deps(Activity activity, ExecutorService worker, Supplier<ChatPanel> chatPanel,
                Supplier<ImageSettings> imageSettings, BooleanSupplier isDestroyed,
                Runnable recomputeBusy, Consumer<CharSequence> setChatStatus,
                Runnable updateControls) {
            this(activity, worker, chatPanel, imageSettings, isDestroyed, recomputeBusy,
                    setChatStatus, updateControls, null);
        }

        Deps(Activity activity, ExecutorService worker, Supplier<ChatPanel> chatPanel,
                Supplier<ImageSettings> imageSettings, BooleanSupplier isDestroyed,
                Runnable recomputeBusy, Consumer<CharSequence> setChatStatus,
                Runnable updateControls, ProcessingTelemetry telemetry) {
            this.activity = activity;
            this.worker = worker;
            this.chatPanel = chatPanel;
            this.imageSettings = imageSettings;
            this.isDestroyed = isDestroyed;
            this.recomputeBusy = recomputeBusy;
            this.setChatStatus = setChatStatus;
            this.updateControls = updateControls;
            this.telemetry = telemetry;
        }
    }

    private final Deps deps;
    private final AtomicInteger activeImports = new AtomicInteger();
    private volatile AtomicBoolean importCancellation;
    private String pendingImagePath = "";
    private String pendingAudioPath = "";
    private final List<ChatSession.Attachment> pendingDocuments = new ArrayList<>();

    AttachmentImportCoordinator(Deps deps) {
        this.deps = deps;
    }

    boolean isImporting() {
        return activeImports.get() > 0;
    }

    boolean cancelImportIfActive() {
        if (!isImporting()) return false;
        AtomicBoolean active = importCancellation;
        if (active != null) active.set(true);
        deps.setChatStatus.accept("Cancelling attachment import…");
        return true;
    }

    void cancelPendingImportSilently() {
        AtomicBoolean active = importCancellation;
        if (active != null) active.set(true);
    }

    private AtomicBoolean beginImport() {
        AtomicBoolean cancellation = new AtomicBoolean();
        importCancellation = cancellation;
        activeImports.incrementAndGet();
        deps.recomputeBusy.run();
        deps.updateControls.run();
        return cancellation;
    }

    private void endImport(AtomicBoolean cancellation) {
        if (importCancellation == cancellation) importCancellation = null;
        int remaining = activeImports.decrementAndGet();
        if (remaining < 0) activeImports.set(0);
        deps.recomputeBusy.run();
    }

    void importSelectedDocuments(Intent data) {
        if (data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        importDocumentUris(uris);
    }

    void importDocumentUris(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        if (pendingDocuments.size() + uris.size() > DocumentAttachmentReader.MAX_FILES) {
            deps.setChatStatus.accept("You can attach up to " + DocumentAttachmentReader.MAX_FILES + " documents.");
            return;
        }
        AtomicBoolean cancellation = beginImport();
        ArrayList<String> loadingLabels = new ArrayList<>(uris.size());
        for (Uri uri : uris) {
            String name = displayNameForUri(uri);
            long size = selectedUriSize(uri);
            loadingLabels.add(size > 0 ? name + " (" + formatByteSize(size) + ")" : name);
        }
        ChatPanel initialPanel = deps.chatPanel.get();
        if (initialPanel != null) initialPanel.setImportingDocuments(loadingLabels);
        String firstLabel = loadingLabels.isEmpty() ? "" : loadingLabels.get(0);
        deps.setChatStatus.accept(firstLabel.isEmpty()
                ? deps.activity.getString(R.string.importing_document)
                : "Extracting " + firstLabel + "…");
        deps.worker.execute(() -> {
            ArrayList<ChatSession.Attachment> imported = new ArrayList<>();
            String error = null;
            boolean cancelled = false;
            try {
                for (int idx = 0; idx < uris.size(); idx++) {
                    Uri uri = uris.get(idx);
                    if (deps.isDestroyed.getAsBoolean() || cancellation.get()) {
                        cancelled = true;
                        break;
                    }
                    File temp = null;
                    long startedAt = System.currentTimeMillis();
                    String displayName = displayNameForUri(uri);
                    String mime = deps.activity.getContentResolver().getType(uri);
                    boolean isPdf = DocumentAttachmentReader.isPdfMimeOrName(mime, displayName);
                    String stageTag = isPdf ? "PDF_IMPORT" : "DOC_IMPORT";
                    try {
                        long size = selectedUriSize(uri);
                        String sizeSuffix = size > 0 ? " (" + formatByteSize(size) + ")" : "";
                        final String progressMsg = "Extracting " + (isPdf ? "PDF" : "file")
                                + ": " + displayName + sizeSuffix + "…";
                        deps.activity.runOnUiThread(() -> {
                            if (!deps.isDestroyed.getAsBoolean() && !cancellation.get()) {
                                deps.setChatStatus.accept(progressMsg);
                            }
                        });
                        recordTelemetry(stageTag, 0L, "start " + displayName + sizeSuffix);
                        long maxBytes = isPdf
                                ? DocumentAttachmentReader.MAX_PDF_BYTES : DocumentAttachmentReader.MAX_TEXT_BYTES;
                        if (size >= 0 && size > maxBytes) {
                            error = isPdf
                                    ? displayName + ": This PDF is too large to attach."
                                    : displayName + ": This file is too large to attach.";
                            recordTelemetry(stageTag, System.currentTimeMillis() - startedAt,
                                    "rejected too_large " + displayName + sizeSuffix);
                            break;
                        }
                        temp = copyUriToCache(uri, "doc-" + System.currentTimeMillis() + "-" + imported.size() + ".tmp",
                                maxBytes, cancellation);
                        if (deps.isDestroyed.getAsBoolean() || cancellation.get()) {
                            cancelled = true;
                            break;
                        }
                        DocumentAttachmentReader.Result result = DocumentAttachmentReader.read(
                                temp, displayName, mime, cancellation);
                        if (cancellation.get()) {
                            cancelled = true;
                            break;
                        }
                        long elapsedMs = System.currentTimeMillis() - startedAt;
                        if (!result.isOk()) {
                            error = result.displayName + ": " + result.error;
                            recordTelemetry(stageTag, elapsedMs, "error " + result.displayName + ": " + result.error);
                            break;
                        }
                        recordTelemetry(stageTag, elapsedMs, "ok " + result.displayName + sizeSuffix
                                + " chars=" + result.text.length()
                                + " ~" + Math.max(1, (result.text.length() + 3) / 4) + "tok");
                        imported.add(ChatSession.Attachment.document(result.displayName, result.text));
                    } catch (Throwable e) {
                        long elapsedMs = System.currentTimeMillis() - startedAt;
                        if (cancellation.get()) {
                            cancelled = true;
                            recordTelemetry(stageTag, elapsedMs, "cancelled " + displayName);
                            break;
                        }
                        String message = e instanceof OutOfMemoryError
                                ? "This file is too large to process on this phone."
                                : (e.getMessage() == null ? "Could not read this file." : e.getMessage());
                        recordTelemetry(stageTag, elapsedMs, "failed " + displayName + ": " + message);
                        error = displayName + ": " + message;
                        break;
                    } finally {
                        if (temp != null && temp.exists()) temp.delete();
                    }
                }
            } finally {
                final String importError = error;
                final boolean wasCancelled = cancelled || cancellation.get();
                final ArrayList<ChatSession.Attachment> ok = imported;
                deps.activity.runOnUiThread(() -> {
                    endImport(cancellation);
                    if (deps.isDestroyed.getAsBoolean()) return;
                    ChatPanel panel = deps.chatPanel.get();
                    if (panel != null) panel.setImportingDocuments(java.util.Collections.emptyList());
                    if (wasCancelled) {
                        deps.setChatStatus.accept("Document import cancelled.");
                    } else if (importError != null) {
                        deps.setChatStatus.accept(importError);
                    } else if (!ok.isEmpty()) {
                        pendingDocuments.addAll(ok);
                        deps.setChatStatus.accept(deps.activity.getString(
                                R.string.documents_attached_status, pendingDocuments.size()));
                    }
                    syncPendingDocumentsUi();
                    deps.updateControls.run();
                });
            }
        });
    }

    void copySelectedPhoto(Uri uri) {
        AtomicBoolean cancellation = beginImport();
        deps.setChatStatus.accept(deps.activity.getString(R.string.importing_photo));
        ImageSettings prepareSettings = deps.imageSettings.get();
        deps.worker.execute(() -> {
            File photo = new File(deps.activity.getCacheDir(), "photo-" + System.currentTimeMillis() + ".img");
            File preparedCopy = null;
            String attachedPath = null;
            String attachedCaption = "";
            String failure = null;
            boolean cancelled = false;
            try {
                if (deps.isDestroyed.getAsBoolean() || cancellation.get()) {
                    cancelled = true;
                } else {
                    Long reportedSize = selectedPhotoSize(uri);
                    if (reportedSize != null && reportedSize > ImageAttachmentFiles.MAX_IMAGE_BYTES) {
                        throw new IOException("Selected image exceeds the 32 MiB limit.");
                    }
                    try (InputStream input = deps.activity.getContentResolver().openInputStream(uri);
                         FileOutputStream output = new FileOutputStream(photo)) {
                        if (input == null) throw new IOException("Could not read the selected photo");
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        long totalBytes = 0;
                        while ((count = input.read(buffer)) != -1) {
                            if (deps.isDestroyed.getAsBoolean() || cancellation.get()) {
                                cancelled = true;
                                break;
                            }
                            totalBytes += count;
                            if (totalBytes > ImageAttachmentFiles.MAX_IMAGE_BYTES) {
                                throw new IOException("Selected image exceeds the 32 MiB limit.");
                            }
                            output.write(buffer, 0, count);
                        }
                        if (!cancelled) output.getFD().sync();
                    }
                    if (!cancelled) {
                        String validationError = ImageAttachmentFiles.validationError(photo);
                        if (validationError != null) throw new IOException(validationError);
                        deps.activity.runOnUiThread(() -> {
                            if (!deps.isDestroyed.getAsBoolean() && !cancellation.get()) {
                                deps.setChatStatus.accept(deps.activity.getString(R.string.preparing_photo));
                            }
                        });
                        ImagePreprocessor.Result prepared = ImagePreprocessor.prepare(deps.activity, photo, prepareSettings);
                        preparedCopy = prepared.file;
                        if (prepared.prepared && !prepared.file.equals(photo)) photo.delete();
                        if (deps.isDestroyed.getAsBoolean() || cancellation.get()) {
                            cancelled = true;
                        } else {
                            attachedPath = prepared.file.getAbsolutePath();
                            attachedCaption = prepared.caption;
                        }
                    }
                }
            } catch (Throwable e) {
                if (cancellation.get()) {
                    cancelled = true;
                } else {
                    failure = e instanceof OutOfMemoryError
                            ? "Selected image is too large to process on this phone."
                            : (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            } finally {
                final boolean wasCancelled = cancelled || cancellation.get() || deps.isDestroyed.getAsBoolean();
                if (wasCancelled || failure != null || attachedPath == null) {
                    photo.delete();
                    if (preparedCopy != null && !preparedCopy.equals(photo)) preparedCopy.delete();
                }
                final String finalPath = wasCancelled ? null : attachedPath;
                final String finalCaption = attachedCaption;
                final String finalError = failure;
                deps.activity.runOnUiThread(() -> {
                    endImport(cancellation);
                    if (deps.isDestroyed.getAsBoolean()) return;
                    if (wasCancelled) {
                        deps.setChatStatus.accept("Photo import cancelled.");
                        deps.updateControls.run();
                    } else if (finalError != null || finalPath == null) {
                        deps.setChatStatus.accept("Photo import failed: " + finalError);
                        deps.updateControls.run();
                    } else {
                        applyPendingMediaOnUi(finalPath, "photo", finalCaption);
                    }
                });
            }
        });
    }

    void prepareCapturedPhoto(String path) {
        AtomicBoolean cancellation = beginImport();
        deps.setChatStatus.accept(deps.activity.getString(R.string.preparing_photo));
        ImageSettings prepareSettings = deps.imageSettings.get();
        deps.worker.execute(() -> {
            File photo = new File(path);
            File preparedCopy = null;
            String attachedPath = null;
            String attachedCaption = "";
            String failure = null;
            boolean cancelled = false;
            try {
                if (deps.isDestroyed.getAsBoolean() || cancellation.get()) {
                    cancelled = true;
                } else {
                    String validationError = ImageAttachmentFiles.validationError(photo);
                    if (validationError != null) throw new IOException(validationError);
                    ImagePreprocessor.Result prepared = ImagePreprocessor.prepare(deps.activity, photo, prepareSettings);
                    preparedCopy = prepared.file;
                    if (prepared.prepared && !prepared.file.equals(photo)) photo.delete();
                    if (deps.isDestroyed.getAsBoolean() || cancellation.get()) {
                        cancelled = true;
                    } else {
                        attachedPath = prepared.file.getAbsolutePath();
                        attachedCaption = prepared.caption;
                    }
                }
            } catch (Throwable e) {
                if (cancellation.get()) {
                    cancelled = true;
                } else {
                    failure = e instanceof OutOfMemoryError
                            ? "Captured photo is too large to process on this phone."
                            : (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            } finally {
                final boolean wasCancelled = cancelled || cancellation.get() || deps.isDestroyed.getAsBoolean();
                if (wasCancelled || failure != null || attachedPath == null) {
                    if (preparedCopy != null && !preparedCopy.equals(photo)) preparedCopy.delete();
                    HistoryMediaCleanup.deleteAttachment(deps.activity, path);
                }
                final String finalPath = wasCancelled ? null : attachedPath;
                final String finalCaption = attachedCaption;
                final String finalError = failure;
                deps.activity.runOnUiThread(() -> {
                    endImport(cancellation);
                    if (deps.isDestroyed.getAsBoolean()) return;
                    if (wasCancelled) {
                        deps.setChatStatus.accept("Photo import cancelled.");
                        deps.updateControls.run();
                    } else if (finalError != null || finalPath == null) {
                        deps.setChatStatus.accept("Photo import failed: " + finalError);
                        deps.updateControls.run();
                    } else {
                        applyPendingMediaOnUi(finalPath, "photo", finalCaption);
                    }
                });
            }
        });
    }

    void onCompletedRecordingAudio(File completed) {
        if (!pendingAudioPath.isEmpty()) HistoryMediaCleanup.deleteAttachment(deps.activity, pendingAudioPath);
        pendingAudioPath = completed.getAbsolutePath();
    }

    List<ChatSession.Attachment> buildPendingAttachments() {
        List<ChatSession.Attachment> attachments = new ArrayList<>();
        if (!pendingImagePath.isEmpty()) {
            attachments.add(new ChatSession.Attachment(pendingImagePath, ChatSession.AttachmentKind.PHOTO));
        }
        if (!pendingAudioPath.isEmpty()) {
            attachments.add(new ChatSession.Attachment(pendingAudioPath, ChatSession.AttachmentKind.VOICE_RECORDING));
        }
        if (!pendingDocuments.isEmpty()) attachments.addAll(pendingDocuments);
        return attachments;
    }

    void clearPendingAfterSubmit() {
        pendingImagePath = "";
        pendingAudioPath = "";
        pendingDocuments.clear();
        ChatPanel panel = deps.chatPanel.get();
        if (panel != null) {
            panel.clearImageAttachment();
            panel.clearDocumentAttachments();
        }
    }

    void clearPendingForRetry() {
        pendingImagePath = "";
        pendingAudioPath = "";
        clearPendingDocuments();
        ChatPanel panel = deps.chatPanel.get();
        if (panel != null) panel.clearImageAttachment();
    }

    void clearAndDeletePending(boolean clearPanel) {
        HistoryMediaCleanup.deleteAttachment(deps.activity, pendingImagePath);
        HistoryMediaCleanup.deleteAttachment(deps.activity, pendingAudioPath);
        pendingImagePath = "";
        pendingAudioPath = "";
        pendingDocuments.clear();
        if (clearPanel) {
            ChatPanel panel = deps.chatPanel.get();
            if (panel != null) {
                panel.clearImageAttachment();
                panel.clearDocumentAttachments();
            }
        }
    }

    String pendingMediaPathForTest() {
        return !pendingAudioPath.isEmpty() ? pendingAudioPath : pendingImagePath;
    }

    String[] pendingMediaPathsForTest() {
        if (pendingImagePath.isEmpty()) {
            return pendingAudioPath.isEmpty() ? new String[0] : new String[] { pendingAudioPath };
        }
        if (pendingAudioPath.isEmpty()) return new String[] { pendingImagePath };
        return new String[] { pendingImagePath, pendingAudioPath };
    }

    private long selectedUriSize(Uri uri) {
        try (Cursor cursor = deps.activity.getContentResolver().query(uri,
                new String[] { OpenableColumns.SIZE }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) return cursor.getLong(column);
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }

    private String displayNameForUri(Uri uri) {
        try (Cursor cursor = deps.activity.getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !cursor.isNull(column)) {
                    String name = cursor.getString(column);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri == null ? null : uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? "document" : last.trim();
    }

    private File copyUriToCache(Uri uri, String name, long maxBytes, AtomicBoolean cancellation) throws IOException {
        File out = new File(deps.activity.getCacheDir(), name);
        try (InputStream input = deps.activity.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(out)) {
            if (input == null) throw new IOException("Could not read the selected file.");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if ((cancellation != null && cancellation.get()) || deps.isDestroyed.getAsBoolean()) {
                    throw new IOException("Document import cancelled.");
                }
                total += count;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new IOException("This file is too large to attach.");
                }
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
            return out;
        } catch (IOException e) {
            out.delete();
            throw e;
        }
    }

    private void removePendingDocument(int index) {
        if (index < 0 || index >= pendingDocuments.size()) return;
        pendingDocuments.remove(index);
        syncPendingDocumentsUi();
        if (pendingDocuments.isEmpty()) {
            deps.setChatStatus.accept("Document removed.");
        } else {
            deps.setChatStatus.accept(deps.activity.getString(
                    R.string.documents_attached_status, pendingDocuments.size()));
        }
        deps.updateControls.run();
    }

    private void syncPendingDocumentsUi() {
        ChatPanel panel = deps.chatPanel.get();
        if (panel == null) return;
        if (pendingDocuments.isEmpty()) {
            panel.clearDocumentAttachments();
            return;
        }
        ArrayList<String> names = new ArrayList<>(pendingDocuments.size());
        for (ChatSession.Attachment doc : pendingDocuments) names.add(formatDocChipLabel(doc));
        panel.setDocumentAttachments(names, this::removePendingDocument);
    }

    private void recordTelemetry(String stage, long durationMs, String detail) {
        if (deps.telemetry != null) {
            deps.telemetry.record(stage, durationMs, detail);
        }
    }

    static String formatByteSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String formatDocChipLabel(ChatSession.Attachment doc) {
        if (doc == null) return "";
        int chars = doc.extractedText == null ? 0 : doc.extractedText.length();
        if (chars <= 0) return doc.displayName;
        int approxTokens = Math.max(1, (chars + 3) / 4);
        String charPart = chars >= 1000
                ? String.format(java.util.Locale.US, "%.1fk chars", chars / 1000.0)
                : chars + " chars";
        String tokPart = approxTokens >= 1000
                ? String.format(java.util.Locale.US, "~%.1fk tok", approxTokens / 1000.0)
                : "~" + approxTokens + " tok";
        return doc.displayName + " · " + charPart + " (" + tokPart + ")";
    }

    private void clearPendingDocuments() {
        pendingDocuments.clear();
        ChatPanel panel = deps.chatPanel.get();
        if (panel != null) panel.clearDocumentAttachments();
    }

    private Long selectedPhotoSize(Uri uri) {
        try (Cursor cursor = deps.activity.getContentResolver().query(uri,
                new String[] { OpenableColumns.SIZE }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) return cursor.getLong(column);
            }
        } catch (Exception ignored) {
            // Some document providers omit size metadata. The bounded stream copy remains authoritative.
        }
        return null;
    }

    private void applyPendingMediaOnUi(String path, String kind, String caption) {
        if ("photo".equals(kind)) {
            if (!pendingImagePath.isEmpty() && !pendingImagePath.equals(path)) {
                HistoryMediaCleanup.deleteAttachment(deps.activity, pendingImagePath);
            }
            pendingImagePath = path;
            ChatPanel panel = deps.chatPanel.get();
            if (panel != null) panel.showImageAttachment(path, caption, () -> removePendingImage(path));
        } else {
            if (!pendingAudioPath.isEmpty() && !pendingAudioPath.equals(path)) {
                HistoryMediaCleanup.deleteAttachment(deps.activity, pendingAudioPath);
            }
            pendingAudioPath = path;
        }
        deps.setChatStatus.accept("photo".equals(kind)
                ? deps.activity.getString(R.string.photo_attached_status)
                : (kind + " attached. Add a question and press Send."));
        deps.updateControls.run();
    }

    private void removePendingImage(String expectedPath) {
        if (!expectedPath.equals(pendingImagePath)) return;
        HistoryMediaCleanup.deleteAttachment(deps.activity, pendingImagePath);
        pendingImagePath = "";
        ChatPanel panel = deps.chatPanel.get();
        if (panel != null) panel.clearImageAttachment();
        deps.setChatStatus.accept("Photo removed. Add a question and press Send.");
        deps.updateControls.run();
    }
}
