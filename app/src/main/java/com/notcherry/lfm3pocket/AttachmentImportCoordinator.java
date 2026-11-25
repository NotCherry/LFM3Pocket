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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coordinates photo, camera, audio, and document attachment import, preprocessing,
 * pending-attachment state, and cleanup outside {@link MainActivity} (#322).
 */
final class AttachmentImportCoordinator {
    static final class Deps {
        final Activity activity;
        final ExecutorService worker;
        final Supplier<ChatPanel> chatPanel;
        final Supplier<ImageSettings> imageSettings;
        final BooleanSupplier isDestroyed;
        final Consumer<Boolean> setBusy;
        final Consumer<CharSequence> setChatStatus;
        final Runnable updateControls;

        Deps(Activity activity, ExecutorService worker, Supplier<ChatPanel> chatPanel,
                Supplier<ImageSettings> imageSettings, BooleanSupplier isDestroyed,
                Consumer<Boolean> setBusy, Consumer<CharSequence> setChatStatus,
                Runnable updateControls) {
            this.activity = activity;
            this.worker = worker;
            this.chatPanel = chatPanel;
            this.imageSettings = imageSettings;
            this.isDestroyed = isDestroyed;
            this.setBusy = setBusy;
            this.setChatStatus = setChatStatus;
            this.updateControls = updateControls;
        }
    }

    private final Deps deps;
    private String pendingImagePath = "";
    private String pendingAudioPath = "";
    private final List<ChatSession.Attachment> pendingDocuments = new ArrayList<>();

    AttachmentImportCoordinator(Deps deps) {
        this.deps = deps;
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
        if (uris.isEmpty()) return;
        if (pendingDocuments.size() + uris.size() > DocumentAttachmentReader.MAX_FILES) {
            deps.setChatStatus.accept("You can attach up to " + DocumentAttachmentReader.MAX_FILES + " documents.");
            return;
        }
        deps.setBusy.accept(true);
        deps.updateControls.run();
        deps.setChatStatus.accept(deps.activity.getString(R.string.importing_document));
        deps.worker.execute(() -> {
            ArrayList<ChatSession.Attachment> imported = new ArrayList<>();
            String error = null;
            for (Uri uri : uris) {
                if (deps.isDestroyed.getAsBoolean()) return;
                File temp = null;
                try {
                    String displayName = displayNameForUri(uri);
                    String mime = deps.activity.getContentResolver().getType(uri);
                    long size = selectedUriSize(uri);
                    long maxBytes = DocumentAttachmentReader.isPdfMimeOrName(mime, displayName)
                            ? DocumentAttachmentReader.MAX_PDF_BYTES : DocumentAttachmentReader.MAX_TEXT_BYTES;
                    if (size >= 0 && size > maxBytes) {
                        error = DocumentAttachmentReader.isPdfMimeOrName(mime, displayName)
                                ? displayName + ": This PDF is too large to attach."
                                : displayName + ": This file is too large to attach.";
                        break;
                    }
                    temp = copyUriToCache(uri, "doc-" + System.currentTimeMillis() + "-" + imported.size() + ".tmp",
                            maxBytes);
                    DocumentAttachmentReader.Result result = DocumentAttachmentReader.read(temp, displayName, mime);
                    if (!result.isOk()) {
                        error = result.displayName + ": " + result.error;
                        break;
                    }
                    imported.add(ChatSession.Attachment.document(result.displayName, result.text));
                } catch (Exception e) {
                    String name = displayNameForUri(uri);
                    String message = e.getMessage() == null ? "Could not read this file." : e.getMessage();
                    error = name + ": " + message;
                    break;
                } finally {
                    if (temp != null && temp.exists()) temp.delete();
                }
            }
            final String importError = error;
            final ArrayList<ChatSession.Attachment> ok = imported;
            deps.activity.runOnUiThread(() -> {
                if (deps.isDestroyed.getAsBoolean()) return;
                deps.setBusy.accept(false);
                if (importError != null) {
                    deps.setChatStatus.accept(importError);
                } else if (!ok.isEmpty()) {
                    pendingDocuments.addAll(ok);
                    deps.setChatStatus.accept(deps.activity.getString(
                            R.string.documents_attached_status, pendingDocuments.size()));
                }
                syncPendingDocumentsUi();
                deps.updateControls.run();
            });
        });
    }

    void copySelectedPhoto(Uri uri) {
        deps.setBusy.accept(true);
        deps.updateControls.run();
        deps.setChatStatus.accept(deps.activity.getString(R.string.importing_photo));
        ImageSettings prepareSettings = deps.imageSettings.get();
        deps.worker.execute(() -> {
            File photo = new File(deps.activity.getCacheDir(), "photo-" + System.currentTimeMillis() + ".img");
            File preparedCopy = null;
            try {
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
                        totalBytes += count;
                        if (totalBytes > ImageAttachmentFiles.MAX_IMAGE_BYTES) {
                            throw new IOException("Selected image exceeds the 32 MiB limit.");
                        }
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                String validationError = ImageAttachmentFiles.validationError(photo);
                if (validationError != null) throw new IOException(validationError);
                deps.activity.runOnUiThread(() -> {
                    if (!deps.isDestroyed.getAsBoolean()) {
                        deps.setChatStatus.accept(deps.activity.getString(R.string.preparing_photo));
                    }
                });
                ImagePreprocessor.Result prepared = ImagePreprocessor.prepare(deps.activity, photo, prepareSettings);
                preparedCopy = prepared.file;
                if (prepared.prepared && !prepared.file.equals(photo)) photo.delete();
                setPendingMedia(prepared.file.getAbsolutePath(), "photo", prepared.caption);
            } catch (Exception e) {
                photo.delete();
                if (preparedCopy != null && !preparedCopy.equals(photo)) preparedCopy.delete();
                deps.activity.runOnUiThread(() -> {
                    deps.setBusy.accept(false);
                    deps.setChatStatus.accept("Photo import failed: " + e.getMessage());
                    deps.updateControls.run();
                });
            }
        });
    }

    void prepareCapturedPhoto(String path) {
        deps.setBusy.accept(true);
        deps.updateControls.run();
        deps.setChatStatus.accept(deps.activity.getString(R.string.preparing_photo));
        ImageSettings prepareSettings = deps.imageSettings.get();
        deps.worker.execute(() -> {
            File photo = new File(path);
            File preparedCopy = null;
            try {
                String validationError = ImageAttachmentFiles.validationError(photo);
                if (validationError != null) throw new IOException(validationError);
                ImagePreprocessor.Result prepared = ImagePreprocessor.prepare(deps.activity, photo, prepareSettings);
                preparedCopy = prepared.file;
                if (prepared.prepared && !prepared.file.equals(photo)) photo.delete();
                setPendingMedia(prepared.file.getAbsolutePath(), "photo", prepared.caption);
            } catch (Exception e) {
                if (preparedCopy != null && !preparedCopy.equals(photo)) preparedCopy.delete();
                HistoryMediaCleanup.deleteAttachment(deps.activity, path);
                deps.activity.runOnUiThread(() -> {
                    deps.setBusy.accept(false);
                    deps.setChatStatus.accept("Photo import failed: " + e.getMessage());
                    deps.updateControls.run();
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

    private File copyUriToCache(Uri uri, String name, long maxBytes) throws IOException {
        File out = new File(deps.activity.getCacheDir(), name);
        try (InputStream input = deps.activity.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(out)) {
            if (input == null) throw new IOException("Could not read the selected file.");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
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
        for (ChatSession.Attachment doc : pendingDocuments) names.add(doc.displayName);
        panel.setDocumentAttachments(names, this::removePendingDocument);
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

    private void setPendingMedia(String path, String kind, String caption) {
        deps.activity.runOnUiThread(() -> {
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
            deps.setBusy.accept(false);
            deps.setChatStatus.accept("photo".equals(kind)
                    ? deps.activity.getString(R.string.photo_attached_status)
                    : (kind + " attached. Add a question and press Send."));
            deps.updateControls.run();
        });
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
