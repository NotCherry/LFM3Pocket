package com.notcherry.lfm3pocket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vision image preparation preferences.
 * <p>
 * Default max side is 512 to match {@code clip.vision.image_size} on LFM3 mmproj GGUFs.
 * LFM3-VL uses native-resolution / dynamic pixel budgets (not fixed 512 tiles), so square
 * crop stays off by default to preserve aspect ratio for the projector.
 */
public final class ImageSettings {
    public enum MaxSide {
        SIDE_384(384, "384"),
        SIDE_512(512, "512"),
        SIDE_768(768, "768"),
        SIDE_1024(1024, "1024"),
        ORIGINAL(Integer.MAX_VALUE, "Original");

        public final int pixels;
        public final String label;

        MaxSide(int pixels, String label) {
            this.pixels = pixels;
            this.label = label;
        }

        public static MaxSide fromPixels(int pixels) {
            for (MaxSide side : values()) {
                if (side.pixels == pixels) return side;
            }
            throw new IllegalArgumentException("Unsupported max image size: " + pixels);
        }

        public static MaxSide fromLabel(String label) {
            for (MaxSide side : values()) {
                if (side.label.equals(label)) return side;
            }
            throw new IllegalArgumentException("Unsupported max image size label: " + label);
        }
    }

    public static final String KEY_MAX_SIDE = "image_max_side";
    public static final String KEY_CROP_SQUARE = "image_crop_square";
    public static final String KEY_JPEG_QUALITY = "image_jpeg_quality";
    public static final String KEY_ENABLED = "image_preprocess_enabled";

    /** On by default: downscale to the projector's nominal 512 px longest side. */
    public static final ImageSettings DEFAULT = new ImageSettings(true, MaxSide.SIDE_512, false, 85);

    public final boolean enabled;
    public final MaxSide maxSide;
    public final boolean cropToSquare;
    public final int jpegQuality;

    public ImageSettings(boolean enabled, MaxSide maxSide, boolean cropToSquare, int jpegQuality) {
        if (maxSide == null) throw new IllegalArgumentException("Max side is required");
        if (jpegQuality < 40 || jpegQuality > 95)
            throw new IllegalArgumentException("JPEG quality must be 40–95");
        this.enabled = enabled;
        this.maxSide = maxSide;
        this.cropToSquare = cropToSquare;
        this.jpegQuality = jpegQuality;
    }

    public boolean needsReencode() {
        return enabled && (maxSide != MaxSide.ORIGINAL || cropToSquare);
    }

    public int maxSidePixels() {
        return maxSide.pixels;
    }

    public String summary() {
        if (!enabled) return "Images · preparation off";
        String crop = cropToSquare ? " · square crop" : "";
        if (maxSide == MaxSide.ORIGINAL && !cropToSquare)
            return "Images · Original · JPEG " + jpegQuality;
        return "Images · max " + maxSide.label + crop + " · JPEG " + jpegQuality;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ImageSettings)) return false;
        ImageSettings settings = (ImageSettings) other;
        return enabled == settings.enabled
                && cropToSquare == settings.cropToSquare
                && jpegQuality == settings.jpegQuality
                && maxSide == settings.maxSide;
    }

    @Override public int hashCode() {
        int result = Boolean.hashCode(enabled);
        result = 31 * result + maxSide.hashCode();
        result = 31 * result + Boolean.hashCode(cropToSquare);
        result = 31 * result + jpegQuality;
        return result;
    }

    /** Serializable map for persistence unit tests and DataStore adapters. */
    public Map<String, Object> toPersistenceMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_ENABLED, enabled);
        map.put(KEY_MAX_SIDE, maxSide.pixels);
        map.put(KEY_CROP_SQUARE, cropToSquare);
        map.put(KEY_JPEG_QUALITY, jpegQuality);
        return Collections.unmodifiableMap(map);
    }

    public static ImageSettings fromPersistenceMap(Map<String, ?> map) {
        if (map == null || map.isEmpty()) return DEFAULT;
        boolean enabled = readBoolean(map.get(KEY_ENABLED), DEFAULT.enabled);
        int maxPixels = readInt(map.get(KEY_MAX_SIDE), DEFAULT.maxSide.pixels);
        boolean crop = readBoolean(map.get(KEY_CROP_SQUARE), DEFAULT.cropToSquare);
        int quality = readInt(map.get(KEY_JPEG_QUALITY), DEFAULT.jpegQuality);
        try {
            return new ImageSettings(enabled, MaxSide.fromPixels(maxPixels), crop, quality);
        } catch (IllegalArgumentException invalid) {
            return DEFAULT;
        }
    }

    private static boolean readBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        return fallback;
    }

    private static int readInt(Object value, int fallback) {
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        return fallback;
    }
}
