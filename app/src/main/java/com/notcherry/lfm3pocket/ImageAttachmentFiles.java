package com.notcherry.lfm3pocket;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/** Validates selected image files and creates bounded previews without decoding full-resolution photos. */
final class ImageAttachmentFiles {
    private static final int MAX_PREVIEW_DIMENSION = 640;
    static final long MAX_IMAGE_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_IMAGE_DIMENSION = 8192;
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;

    private ImageAttachmentFiles() {}

    static String validationError(File file) {
        if (file == null || !file.isFile() || file.length() == 0)
            return "Selected file is not a readable image.";
        if (file.length() > MAX_IMAGE_BYTES)
            return "Selected image exceeds the 32 MiB limit.";

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            return "Selected file is not a readable image.";
        if ((long) bounds.outWidth * bounds.outHeight > MAX_IMAGE_PIXELS)
            return "Image dimensions exceed the supported 40 megapixel limit.";
        if (bounds.outWidth > MAX_IMAGE_DIMENSION || bounds.outHeight > MAX_IMAGE_DIMENSION)
            return "Image dimensions exceed the supported 8192 pixel limit.";

        Bitmap preview = decodePreview(file);
        if (preview == null) return "Selected file is not a readable image.";
        preview.recycle();
        return null;
    }

    static Bitmap decodePreview(File file) {
        if (file == null || !file.isFile() || file.length() == 0 || file.length() > MAX_IMAGE_BYTES) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        if ((long) bounds.outWidth * bounds.outHeight > MAX_IMAGE_PIXELS
                || bounds.outWidth > MAX_IMAGE_DIMENSION || bounds.outHeight > MAX_IMAGE_DIMENSION) return null;

        int sampleSize = 1;
        while (bounds.outWidth / sampleSize > MAX_PREVIEW_DIMENSION
                || bounds.outHeight / sampleSize > MAX_PREVIEW_DIMENSION) {
            sampleSize *= 2;
        }
        BitmapFactory.Options preview = new BitmapFactory.Options();
        preview.inSampleSize = sampleSize;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), preview);
    }

    /**
     * Crash-safety probe: decode a tightly bounded bitmap before native mtmd sees the file.
     * Returns a user-facing error string, or null when the probe succeeds.
     * Does not write a downscaled inference copy (separate default-downscale work owns that).
     */
    static String boundedDecodeError(File file) {
        if (file == null || !file.isFile()) return "Selected file is not a readable image.";
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        } catch (Throwable error) {
            return "Could not read this image safely on this phone.";
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            return "Selected file is not a readable image.";
        int sampleSize = 1;
        while (bounds.outWidth / sampleSize > MAX_PREVIEW_DIMENSION
                || bounds.outHeight / sampleSize > MAX_PREVIEW_DIMENSION) {
            sampleSize *= 2;
        }
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inSampleSize = sampleSize;
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), probe);
            if (bitmap == null) return "Could not decode this image for on-device vision.";
            return null;
        } catch (OutOfMemoryError error) {
            return "This image is too large to decode safely on this phone. Try a smaller photo.";
        } catch (Throwable error) {
            return "Could not decode this image for on-device vision.";
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }
}
