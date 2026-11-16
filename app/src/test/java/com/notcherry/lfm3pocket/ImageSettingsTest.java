package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ImageSettingsTest {
    @Test public void defaultsMatchProjectorFriendlyPhonePreset() {
        assertTrue(ImageSettings.DEFAULT.enabled);
        assertEquals(ImageSettings.MaxSide.SIDE_512, ImageSettings.DEFAULT.maxSide);
        assertEquals(512, ImageSettings.DEFAULT.maxSidePixels());
        assertFalse(ImageSettings.DEFAULT.cropToSquare);
        assertEquals(85, ImageSettings.DEFAULT.jpegQuality);
        assertTrue(ImageSettings.DEFAULT.needsReencode());
        assertTrue(ImageSettings.DEFAULT.summary().contains("512"));
    }

    @Test public void originalWithoutCropSkipsReencodeWhileQualityStillValidates() {
        ImageSettings original = new ImageSettings(true, ImageSettings.MaxSide.ORIGINAL, false, 90);
        assertFalse(original.needsReencode());
        assertTrue(new ImageSettings(true, ImageSettings.MaxSide.ORIGINAL, true, 90).needsReencode());
        assertFalse(new ImageSettings(false, ImageSettings.MaxSide.SIDE_384, true, 50).needsReencode());
    }

    @Test public void persistenceRoundTripPreservesValues() {
        ImageSettings settings = new ImageSettings(true, ImageSettings.MaxSide.SIDE_768, true, 70);
        Map<String, Object> map = new HashMap<>(settings.toPersistenceMap());
        ImageSettings restored = ImageSettings.fromPersistenceMap(map);
        assertEquals(settings.enabled, restored.enabled);
        assertEquals(settings.maxSide, restored.maxSide);
        assertEquals(settings.cropToSquare, restored.cropToSquare);
        assertEquals(settings.jpegQuality, restored.jpegQuality);
        assertEquals(settings.summary(), restored.summary());
    }

    @Test public void corruptedPersistenceFallsBackToDefaults() {
        assertEquals(ImageSettings.DEFAULT, ImageSettings.fromPersistenceMap(null));
        assertEquals(ImageSettings.DEFAULT, ImageSettings.fromPersistenceMap(Map.of()));
        Map<String, Object> bad = new HashMap<>();
        bad.put(ImageSettings.KEY_ENABLED, true);
        bad.put(ImageSettings.KEY_MAX_SIDE, 999);
        bad.put(ImageSettings.KEY_CROP_SQUARE, false);
        bad.put(ImageSettings.KEY_JPEG_QUALITY, 85);
        assertEquals(ImageSettings.DEFAULT.maxSide, ImageSettings.fromPersistenceMap(bad).maxSide);
    }

    @Test public void jpegQualityBoundsAreEnforced() {
        int[] invalid = { 0, 39, 96, 100, -1 };
        for (int quality : invalid) {
            try {
                new ImageSettings(true, ImageSettings.MaxSide.SIDE_512, false, quality);
                fail("accepted quality " + quality);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("JPEG"));
            }
        }
        new ImageSettings(true, ImageSettings.MaxSide.SIDE_384, false, 40);
        new ImageSettings(true, ImageSettings.MaxSide.SIDE_1024, true, 95);
    }

    @Test public void maxSideLabelsRoundTrip() {
        for (ImageSettings.MaxSide side : ImageSettings.MaxSide.values()) {
            assertEquals(side, ImageSettings.MaxSide.fromLabel(side.label));
            assertEquals(side, ImageSettings.MaxSide.fromPixels(side.pixels));
        }
    }
}
