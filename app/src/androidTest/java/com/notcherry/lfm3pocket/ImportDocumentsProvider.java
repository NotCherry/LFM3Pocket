package com.notcherry.lfm3pocket;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.*;

/** Real SAF fixture, packaged only in the instrumentation APK. */
public final class ImportDocumentsProvider extends DocumentsProvider {
    private static final String[] ROOT = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES};
    private static final String[] DOC = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS};
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection == null ? ROOT : projection);
        c.newRow().add(Root.COLUMN_ROOT_ID, "fixture").add(Root.COLUMN_DOCUMENT_ID, "root")
                .add(Root.COLUMN_TITLE, "LFM test files").add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_MIME_TYPES, "*/*");
        return c;
    }
    private void row(MatrixCursor c, String id) {
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, id)
                .add(Document.COLUMN_DISPLAY_NAME, id.equals("root") ? "LFM test files" : id)
                .add(Document.COLUMN_MIME_TYPE, id.equals("root") ? Document.MIME_TYPE_DIR : "application/octet-stream")
                .add(Document.COLUMN_SIZE, id.equals("root") ? 0 : 1024 * 1024)
                .add(Document.COLUMN_FLAGS, 0);
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor c = new MatrixCursor(projection == null ? DOC : projection); row(c, id); return c;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sort) {
        MatrixCursor c = new MatrixCursor(projection == null ? DOC : projection);
        row(c, "valid.gguf"); row(c, "damaged.gguf"); return c;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!id.equals("valid.gguf") && !id.equals("damaged.gguf")) throw new FileNotFoundException(id);
        File f = new File(getContext().getCacheDir(), id);
        try (RandomAccessFile out = new RandomAccessFile(f, "rw")) {
            out.setLength(1024 * 1024);
            out.seek(0);
            out.write(new byte[]{(byte)(id.equals("valid.gguf") ? 'G' : 0), 'G', 'U', 'F', 3, 0, 0, 0});
        } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
