package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CactusWhistleModelStoreTest {
    @Test public void partialFileUsesManifestName() throws Exception {
        File dir = Files.createTempDirectory("whistle-store").toFile();
        CactusWhistleModelStore store = new CactusWhistleModelStore(dir);
        assertEquals(CactusWhistleModelManifest.FILE_NAME + ".partial", store.partialFile().getName());
        assertTrue(store.partialFile().getParentFile().equals(dir));
    }
}