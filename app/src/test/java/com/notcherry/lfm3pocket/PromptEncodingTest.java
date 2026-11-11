package com.notcherry.lfm3pocket;

import org.junit.Test;
import static org.junit.Assert.*;

public class PromptEncodingTest {
    @Test public void supplementaryCharactersAndNulUseStandardUtf8Bytes() {
        assertArrayEquals(new byte[]{0x41,0,(byte)0xF0,(byte)0x9F,(byte)0x90,(byte)0x88},
                PromptEncoding.encode("A\u0000🐈"));
        assertArrayEquals(new byte[]{0x5A,0x61,(byte)0xC5,(byte)0xBC,(byte)0xC3,(byte)0xB3,
                (byte)0xC5,(byte)0x82,(byte)0xC4,(byte)0x87},PromptEncoding.encode("Zażółć"));
    }
    @Test public void malformedUtf16UsesJavaUtf8ReplacementWithoutDamagingAdjacentText() {
        assertArrayEquals(new byte[]{0x3F,0x41,0x3F},PromptEncoding.encode("\uD800A\uDC00"));
    }
}
