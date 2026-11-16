package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class ExternalModelStoreTest {
    @Test public void serializesPersistableUriEntriesAcrossRestarts() {
        List<ExternalModelStore.Entry> entries = Arrays.asList(
                new ExternalModelStore.Entry("content://com.android.externalstorage.documents/document/primary%3AModels%2Fmodel.gguf",
                        "model.gguf", false),
                new ExternalModelStore.Entry("content://com.android.externalstorage.documents/document/primary%3AModels%2Fmmproj.gguf",
                        "mmproj.gguf", true));
        String json = ExternalModelStore.serialize(entries);
        List<ExternalModelStore.Entry> restored = ExternalModelStore.deserialize(json);
        assertEquals(2, restored.size());
        assertEquals(entries.get(0).uri, restored.get(0).uri);
        assertEquals("model.gguf", restored.get(0).displayName);
        assertFalse(restored.get(0).projector);
        assertTrue(restored.get(1).projector);
        assertTrue(ExternalModelStore.deserialize("{not-json").isEmpty());
        assertTrue(ExternalModelStore.deserialize("").isEmpty());
    }
}
