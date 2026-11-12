package com.notcherry.lfm3pocket;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
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
                .add(Document.COLUMN_MIME_TYPE, id.equals("root") ? Document.MIME_TYPE_DIR
                        : id.endsWith(".jpg") ? "image/jpeg"
                        : id.endsWith(".png") ? "image/png" : "application/octet-stream")
                .add(Document.COLUMN_SIZE, id.equals("root") ? 0
                        : id.equals("oversized-photo.jpg") ? ImageAttachmentFiles.MAX_IMAGE_BYTES + 1 : 1024 * 1024)
                .add(Document.COLUMN_FLAGS, 0);
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor c = new MatrixCursor(projection == null ? DOC : projection); row(c, id); return c;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sort) {
        MatrixCursor c = new MatrixCursor(projection == null ? DOC : projection);
        row(c, "valid.gguf"); row(c, "damaged.gguf");
        row(c, "projector.gguf"); row(c, "wrong-family.gguf");
        row(c, "valid-photo.jpg"); row(c, "replacement-photo.jpg"); row(c, "invalid-photo.jpg");
        row(c, "truncated-photo.png"); row(c, "large-dimensions-photo.jpg"); row(c, "oversized-photo.jpg"); return c;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!java.util.Arrays.asList("valid.gguf","damaged.gguf","projector.gguf","wrong-family.gguf",
                "valid-photo.jpg","replacement-photo.jpg","invalid-photo.jpg","truncated-photo.png",
                "large-dimensions-photo.jpg","oversized-photo.jpg").contains(id))
            throw new FileNotFoundException(id);
        File f = new File(getContext().getCacheDir(), id);
        try {
            if (id.equals("oversized-photo.jpg")) {
                try (FileOutputStream output = new FileOutputStream(f)) {
                    byte[] block=new byte[64*1024];long remaining=ImageAttachmentFiles.MAX_IMAGE_BYTES+1;
                    while(remaining>0){int count=(int)Math.min(block.length,remaining);output.write(block,0,count);remaining-=count;}
                }
            } else if (id.equals("large-dimensions-photo.jpg")) {
                Bitmap bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
                java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, encoded);
                bitmap.recycle();
                byte[] jpeg = encoded.toByteArray();
                if (!putJpegDimensions(jpeg, 9000, 5000)) throw new IOException("JPEG frame not found");
                try (FileOutputStream output = new FileOutputStream(f)) { output.write(jpeg); }
            } else if (id.endsWith(".png")) {
                Bitmap bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
                java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded);
                bitmap.recycle();
                byte[] validHeader = encoded.toByteArray();
                try (FileOutputStream output = new FileOutputStream(f)) {
                    output.write(validHeader, 0, 33); // Valid PNG signature and IHDR, but no pixel data.
                }
            } else if (id.endsWith(".jpg")) {
                try (FileOutputStream output = new FileOutputStream(f)) {
                    if (id.equals("invalid-photo.jpg")) {
                        output.write("not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else {
                        Bitmap bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(id.equals("valid-photo.jpg") ? 0xffd54a35 : 0xff2879bb);
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output);
                        bitmap.recycle();
                    }
                }
            } else {
                TestGgufFixtures.write(f,id.equals("projector.gguf") || id.equals("wrong-family.gguf"),
                        id.equals("wrong-family.gguf") ? "LFM3-VL-2B" : "LFM3-Omni-2B");
                if(id.equals("damaged.gguf")) try(RandomAccessFile out = new RandomAccessFile(f,"rw")) {
                    out.seek(0); out.write(0);
                }
            }
        } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    private static boolean putJpegDimensions(byte[] jpeg,int width,int height){
        int offset=2;
        while(offset+9<jpeg.length){
            if((jpeg[offset]&0xff)!=0xff){offset++;continue;}
            int marker=jpeg[offset+1]&0xff;
            if(marker==0xd8||marker==0xd9||marker==0x01||(marker>=0xd0&&marker<=0xd7)){offset+=2;continue;}
            int length=((jpeg[offset+2]&0xff)<<8)|(jpeg[offset+3]&0xff);
            if(length<2||offset+2+length>jpeg.length)return false;
            if((marker>=0xc0&&marker<=0xc3)||(marker>=0xc5&&marker<=0xc7)
                    ||(marker>=0xc9&&marker<=0xcb)||(marker>=0xcd&&marker<=0xcf)){
                jpeg[offset+5]=(byte)(height>>>8);jpeg[offset+6]=(byte)height;
                jpeg[offset+7]=(byte)(width>>>8);jpeg[offset+8]=(byte)width;
                return true;
            }
            offset+=2+length;
        }
        return false;
    }
}
