package com.notcherry.lfm3pocket;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ImageGeometryTest {
    @Test public void orientedSizeRespectsExifRotationAndTranspose() {
        ImageGeometry.Size landscape = ImageGeometry.orientedSize(4032, 3024, ImageGeometry.ORIENTATION_NORMAL);
        assertEquals(4032, landscape.width);
        assertEquals(3024, landscape.height);
        assertFalse(ImageGeometry.swapsAxes(ImageGeometry.ORIENTATION_NORMAL));

        ImageGeometry.Size rotated = ImageGeometry.orientedSize(4032, 3024, ImageGeometry.ORIENTATION_ROTATE_90);
        assertEquals(3024, rotated.width);
        assertEquals(4032, rotated.height);
        assertTrue(ImageGeometry.swapsAxes(ImageGeometry.ORIENTATION_ROTATE_90));

        ImageGeometry.Size rotate270 = ImageGeometry.orientedSize(100, 200, ImageGeometry.ORIENTATION_ROTATE_270);
        assertEquals(new ImageGeometry.Size(200, 100), rotate270);
        assertEquals(new ImageGeometry.Size(200, 100),
                ImageGeometry.orientedSize(100, 200, ImageGeometry.ORIENTATION_TRANSPOSE));
        assertEquals(new ImageGeometry.Size(200, 100),
                ImageGeometry.orientedSize(100, 200, ImageGeometry.ORIENTATION_TRANSVERSE));
        assertEquals(new ImageGeometry.Size(100, 200),
                ImageGeometry.orientedSize(100, 200, ImageGeometry.ORIENTATION_ROTATE_180));
    }

    @Test public void targetSizeDownscalesLongestSideAndOptionallyCropsSquare() {
        ImageGeometry.Size phone = ImageGeometry.targetSize(4032, 3024, 512, false);
        assertEquals(512, phone.longestSide());
        assertEquals(512, phone.width);
        assertEquals(384, phone.height);

        ImageGeometry.Size alreadySmall = ImageGeometry.targetSize(320, 240, 512, false);
        assertEquals(new ImageGeometry.Size(320, 240), alreadySmall);

        ImageGeometry.Size original = ImageGeometry.targetSize(4032, 3024, Integer.MAX_VALUE, false);
        assertEquals(new ImageGeometry.Size(4032, 3024), original);

        ImageGeometry.Size square = ImageGeometry.targetSize(4032, 3024, 512, true);
        assertEquals(new ImageGeometry.Size(384, 384), square);

        ImageGeometry.Size tall = ImageGeometry.targetSize(1080, 1920, 768, false);
        assertEquals(432, tall.width);
        assertEquals(768, tall.height);
    }

    @Test public void sampleSizeNeverUndersamplesBelowTarget() {
        assertEquals(1, ImageGeometry.sampleSize(512, 512, 512, 512));
        assertEquals(4, ImageGeometry.sampleSize(4032, 3024, 512, 384));
        assertEquals(2, ImageGeometry.sampleSize(2000, 1500, 512, 384));
        assertEquals(1, ImageGeometry.sampleSize(600, 400, 512, 384));
    }

    @Test public void centerCropRectIsCentered() {
        assertArrayEquals(new int[] { 250, 0, 500, 500 },
                ImageGeometry.centerCropRect(1000, 500, 500, 500));
        assertArrayEquals(new int[] { 0, 250, 500, 500 },
                ImageGeometry.centerCropRect(500, 1000, 500, 500));
    }

    @Test public void invalidDimensionsAreRejected() {
        try {
            ImageGeometry.targetSize(0, 10, 512, false);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Oriented"));
        }
        try {
            ImageGeometry.sampleSize(10, 10, 0, 10);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("positive"));
        }
    }
}
