package com.notcherry.lfm3pocket;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CactusWhistleModelManifestTest {
    @Test
    public void pinsRevisionHashAndFile() {
        assertEquals(40, CactusWhistleModelManifest.HF_REVISION.length());
        assertEquals("whistle.cact", CactusWhistleModelManifest.FILE_NAME);
        assertTrue(CactusWhistleModelManifest.HF_BASE.contains(CactusWhistleModelManifest.HF_REVISION));
        assertEquals(64, CactusWhistleModelManifest.SHA256.length());
    }
}
