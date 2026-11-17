package com.notcherry.lfm3pocket;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TinyVocaPackManifestTest {
    @Test
    public void pinsRevisionAndHashes() {
        assertEquals(64, TinyVocaPackManifest.HF_REVISION.length());
        assertTrue(TinyVocaPackManifest.HF_BASE.contains(TinyVocaPackManifest.HF_REVISION));
        assertEquals(9, TinyVocaPackManifest.expectedSha256().size());
        assertEquals(64, TinyVocaPackManifest.expectedSha256().get("onnx/model.onnx").length());
    }
}