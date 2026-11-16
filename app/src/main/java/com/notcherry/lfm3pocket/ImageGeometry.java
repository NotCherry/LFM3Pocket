package com.notcherry.lfm3pocket;

/**
 * Pure size and orientation math for vision image preparation.
 * Kept free of Android framework types so JVM unit tests can cover it.
 */
public final class ImageGeometry {
    /** Matches {@code android.media.ExifInterface} orientation constants. */
    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int ORIENTATION_NORMAL = 1;
    public static final int ORIENTATION_FLIP_HORIZONTAL = 2;
    public static final int ORIENTATION_ROTATE_180 = 3;
    public static final int ORIENTATION_FLIP_VERTICAL = 4;
    public static final int ORIENTATION_TRANSPOSE = 5;
    public static final int ORIENTATION_ROTATE_90 = 6;
    public static final int ORIENTATION_TRANSVERSE = 7;
    public static final int ORIENTATION_ROTATE_270 = 8;

    public static final class Size {
        public final int width;
        public final int height;

        public Size(int width, int height) {
            if (width < 0 || height < 0) throw new IllegalArgumentException("Size must be non-negative");
            this.width = width;
            this.height = height;
        }

        public int longestSide() {
            return Math.max(width, height);
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Size)) return false;
            Size size = (Size) other;
            return width == size.width && height == size.height;
        }

        @Override public int hashCode() {
            return 31 * width + height;
        }

        @Override public String toString() {
            return width + "×" + height;
        }
    }

    private ImageGeometry() {}

    /** True when EXIF orientation swaps width and height for display. */
    public static boolean swapsAxes(int orientation) {
        return orientation == ORIENTATION_TRANSPOSE
                || orientation == ORIENTATION_ROTATE_90
                || orientation == ORIENTATION_TRANSVERSE
                || orientation == ORIENTATION_ROTATE_270;
    }

    public static Size orientedSize(int encodedWidth, int encodedHeight, int orientation) {
        if (encodedWidth <= 0 || encodedHeight <= 0)
            throw new IllegalArgumentException("Encoded size must be positive");
        if (swapsAxes(orientation)) return new Size(encodedHeight, encodedWidth);
        return new Size(encodedWidth, encodedHeight);
    }

    /**
     * Target output size after optional longest-side downscale and optional center square crop.
     * {@code maxSide} of {@link Integer#MAX_VALUE} means no downscale (Original).
     */
    public static Size targetSize(int orientedWidth, int orientedHeight, int maxSide, boolean cropToSquare) {
        if (orientedWidth <= 0 || orientedHeight <= 0)
            throw new IllegalArgumentException("Oriented size must be positive");
        if (maxSide < 1) throw new IllegalArgumentException("maxSide must be at least 1");

        int width = orientedWidth;
        int height = orientedHeight;
        int longest = Math.max(width, height);
        if (longest > maxSide) {
            double scale = (double) maxSide / (double) longest;
            width = Math.max(1, (int) Math.round(width * scale));
            height = Math.max(1, (int) Math.round(height * scale));
        }
        if (cropToSquare) {
            int side = Math.min(width, height);
            return new Size(side, side);
        }
        return new Size(width, height);
    }

    /**
     * Power-of-two {@code inSampleSize} so the decoded bitmap is at least as large as the target
     * on both axes (never undersamples below the needed pixels).
     */
    public static int sampleSize(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0)
            throw new IllegalArgumentException("Dimensions must be positive");
        int sample = 1;
        while ((sourceWidth / (sample * 2)) >= targetWidth
                && (sourceHeight / (sample * 2)) >= targetHeight) {
            sample *= 2;
        }
        return sample;
    }

    /** Center-crop rectangle in source bitmap coordinates. */
    public static int[] centerCropRect(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0)
            throw new IllegalArgumentException("Dimensions must be positive");
        int width = Math.min(sourceWidth, targetWidth);
        int height = Math.min(sourceHeight, targetHeight);
        if (targetWidth == targetHeight) {
            int side = Math.min(sourceWidth, sourceHeight);
            width = side;
            height = side;
        }
        int left = Math.max(0, (sourceWidth - width) / 2);
        int top = Math.max(0, (sourceHeight - height) / 2);
        return new int[] { left, top, width, height };
    }
}
