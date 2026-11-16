package com.notcherry.lfm3pocket;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Build;
import android.util.Size;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Downscales / optionally crops images for the mtmd projector path without mutating the
 * caller's original file. Decodes with {@code inSampleSize} (and ImageDecoder target size
 * on API 28+) so full-resolution bitmaps are never allocated for large phone photos.
 */
public final class ImagePreprocessor {
    public static final class Result {
        public final File file;
        public final int width;
        public final int height;
        public final boolean prepared;
        public final String caption;

        Result(File file, int width, int height, boolean prepared) {
            this.file = file;
            this.width = width;
            this.height = height;
            this.prepared = prepared;
            this.caption = prepared
                    ? "Prepared " + width + "×" + height
                    : "Original " + width + "×" + height;
        }
    }

    private ImagePreprocessor() {}

    /**
     * Prepares {@code source} for vision inference into a new cache file.
     * The source file is never overwritten.
     */
    public static Result prepare(Context context, File source, ImageSettings settings) throws IOException {
        if (context == null || source == null || settings == null)
            throw new IllegalArgumentException("Missing preprocessor input");
        if (!source.isFile() || source.length() == 0)
            throw new IOException("Selected file is not a readable image.");

        Bounds bounds = readBounds(source);
        int orientation = readOrientation(source);
        ImageGeometry.Size oriented = ImageGeometry.orientedSize(bounds.width, bounds.height, orientation);
        boolean orientationChange = orientation != ExifInterface.ORIENTATION_NORMAL
                && orientation != ExifInterface.ORIENTATION_UNDEFINED
                && orientation != 0;

        // Pass through only when preparation is off, or Original+no-crop with upright EXIF.
        // Otherwise write a new JPEG so mtmd sees upright pixels without touching the source file.
        if (!settings.enabled || (!settings.needsReencode() && !orientationChange)) {
            return new Result(source, oriented.width, oriented.height, false);
        }

        ImageGeometry.Size target = ImageGeometry.targetSize(
                oriented.width,
                oriented.height,
                settings.enabled ? settings.maxSidePixels() : Integer.MAX_VALUE,
                settings.enabled && settings.cropToSquare);
        Bitmap decoded = decodeSampled(source, bounds, target, orientation);
        if (decoded == null) throw new IOException("Selected file is not a readable image.");

        Bitmap orientedBitmap = applyOrientation(decoded, orientation);
        if (orientedBitmap != decoded) decoded.recycle();

        Bitmap output = scaleAndCrop(orientedBitmap, target, settings.cropToSquare);
        if (output != orientedBitmap) orientedBitmap.recycle();

        int outWidth = output.getWidth();
        int outHeight = output.getHeight();
        File prepared = new File(context.getCacheDir(), visionFileName(source.getName()));
        try {
            writeJpeg(output, prepared, settings.jpegQuality);
        } finally {
            output.recycle();
        }
        if (!prepared.isFile() || prepared.length() == 0) {
            prepared.delete();
            throw new IOException("Could not write the prepared image.");
        }
        return new Result(prepared, outWidth, outHeight, true);
    }

    /** Human-readable caption for an already-known result size. */
    public static String captionFor(int width, int height, boolean prepared) {
        return prepared ? "Prepared " + width + "×" + height : "Original " + width + "×" + height;
    }

    private static String visionFileName(String sourceName) {
        String base = sourceName == null ? "photo" : sourceName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        if (base.startsWith("camera-") || base.startsWith("photo-"))
            return base + "-vision.jpg";
        return "photo-" + System.currentTimeMillis() + "-vision.jpg";
    }

    private static final class Bounds {
        final int width;
        final int height;

        Bounds(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static Bounds readBounds(File source) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0)
            throw new IOException("Selected file is not a readable image.");
        return new Bounds(options.outWidth, options.outHeight);
    }

    private static int readOrientation(File source) {
        try (InputStream input = new FileInputStream(source)) {
            ExifInterface exif = new ExifInterface(input);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    @Nullable
    private static Bitmap decodeSampled(File source, Bounds bounds, ImageGeometry.Size target,
                                        int orientation) throws IOException {
        // Decode against the encoded (pre-rotation) pixel grid, but aim for enough pixels
        // to cover the oriented target after rotation/crop.
        int decodeTargetW = target.width;
        int decodeTargetH = target.height;
        if (ImageGeometry.swapsAxes(orientation)) {
            decodeTargetW = target.height;
            decodeTargetH = target.width;
        }
        // Prefer a little headroom so createScaledBitmap quality stays good.
        decodeTargetW = Math.max(decodeTargetW, Math.min(bounds.width, decodeTargetW * 2));
        decodeTargetH = Math.max(decodeTargetH, Math.min(bounds.height, decodeTargetH * 2));

        if (Build.VERSION.SDK_INT >= 28) {
            try {
                android.graphics.ImageDecoder.Source decoderSource =
                        android.graphics.ImageDecoder.createSource(source);
                final int finalW = decodeTargetW;
                final int finalH = decodeTargetH;
                return android.graphics.ImageDecoder.decodeBitmap(decoderSource, (decoder, info, src) -> {
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    Size size = info.getSize();
                    int sample = ImageGeometry.sampleSize(size.getWidth(), size.getHeight(), finalW, finalH);
                    int outW = Math.max(1, size.getWidth() / sample);
                    int outH = Math.max(1, size.getHeight() / sample);
                    decoder.setTargetSize(outW, outH);
                });
            } catch (IOException decodeFailed) {
                // Fall through to BitmapFactory.
            }
        }

        int sample = ImageGeometry.sampleSize(bounds.width, bounds.height, decodeTargetW, decodeTargetH);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException("Selected file is not a readable image.");
        return bitmap;
    }

    private static Bitmap applyOrientation(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;
            case ExifInterface.ORIENTATION_NORMAL:
            case ExifInterface.ORIENTATION_UNDEFINED:
            default:
                return source;
        }
        Bitmap transformed = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        return transformed == null ? source : transformed;
    }

    private static Bitmap scaleAndCrop(Bitmap source, ImageGeometry.Size target, boolean cropToSquare) {
        if (cropToSquare) {
            int[] rect = ImageGeometry.centerCropRect(source.getWidth(), source.getHeight(),
                    target.width, target.height);
            Bitmap cropped = Bitmap.createBitmap(source, rect[0], rect[1], rect[2], rect[3]);
            if (cropped.getWidth() == target.width && cropped.getHeight() == target.height) return cropped;
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, target.width, target.height, true);
            if (scaled != cropped) cropped.recycle();
            return scaled;
        }
        if (source.getWidth() == target.width && source.getHeight() == target.height) return source;
        return Bitmap.createScaledBitmap(source, target.width, target.height, true);
    }

    private static void writeJpeg(Bitmap bitmap, File destination, int quality) throws IOException {
        File partial = new File(destination.getAbsolutePath() + ".partial");
        try (FileOutputStream output = new FileOutputStream(partial)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                throw new IOException("JPEG compression failed.");
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            partial.delete();
            throw new IOException("Could not replace the prepared image.");
        }
        if (!partial.renameTo(destination)) {
            partial.delete();
            throw new IOException("Could not finalize the prepared image.");
        }
    }
}
