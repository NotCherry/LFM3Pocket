package com.notcherry.lfm3pocket;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/** Validates selected image files and creates bounded previews without decoding full-resolution photos. */
final class ImageAttachmentFiles {
    private static final int MAX_PREVIEW_DIMENSION = 640;

    private ImageAttachmentFiles() {}

    static boolean isReadableImage(File file) {
        if (file == null || !file.isFile() || file.length() == 0) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return bounds.outWidth > 0 && bounds.outHeight > 0;
    }

    static Bitmap decodePreview(File file) {
        if (!isReadableImage(file)) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

        int sampleSize = 1;
        while (bounds.outWidth / sampleSize > MAX_PREVIEW_DIMENSION
                || bounds.outHeight / sampleSize > MAX_PREVIEW_DIMENSION) {
            sampleSize *= 2;
        }
        BitmapFactory.Options preview = new BitmapFactory.Options();
        preview.inSampleSize = sampleSize;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), preview);
    }
}
