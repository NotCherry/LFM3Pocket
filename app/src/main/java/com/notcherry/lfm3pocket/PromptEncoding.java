package com.notcherry.lfm3pocket;

import java.nio.charset.StandardCharsets;

/** Standard UTF-8 payload for llama.cpp. Malformed UTF-16 uses the Java UTF-8 '?' replacement. */
final class PromptEncoding {
    private PromptEncoding() {}
    static byte[] encode(String prompt) { return prompt.getBytes(StandardCharsets.UTF_8); }
}
