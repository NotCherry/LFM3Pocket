package com.notcherry.lfm3pocket;

import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TinyVocaOnnxRuntimeCancellationTest {
    @Test
    public void checkNotCancelledThrowsWhenFlagSet() {
        SpeechSynthesisCancelled thrown = assertThrows(SpeechSynthesisCancelled.class,
                () -> TinyVocaOnnxRuntime.checkNotCancelled(() -> true));
        assertTrue(thrown.getMessage().contains("cancelled"));
    }
}