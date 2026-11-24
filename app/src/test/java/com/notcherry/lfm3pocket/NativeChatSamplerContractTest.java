package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Guards the Liquid AI LFM3 chat sampling contract across both sequential and ngram-mod
 * decode paths so repeat_penalty cannot silently regress and re-introduce infinite loops.
 */
public final class NativeChatSamplerContractTest {
    @Test public void chatSamplerAppliesRepeatPenaltyAfterTopKAndSharesBothDecodePaths() throws Exception {
        Path root = repoRoot();
        String spec = read(root.resolve("app/src/main/cpp/speculative_decode.cpp"));
        String nat = read(root.resolve("app/src/main/cpp/native.cpp"));

        int fnStart = spec.indexOf("llama_sampler * make_chat_sampler(");
        assertTrue("make_chat_sampler missing from speculative_decode.cpp", fnStart >= 0);
        int fnEnd = spec.indexOf("\n}\n", fnStart);
        assertTrue("make_chat_sampler body not terminated", fnEnd > fnStart);
        String body = spec.substring(fnStart, fnEnd);

        int topK = body.indexOf("llama_sampler_init_top_k");
        int penalties = body.indexOf("llama_sampler_init_penalties");
        int temp = body.indexOf("llama_sampler_init_temp");
        assertTrue("top_k missing in make_chat_sampler", topK >= 0);
        assertTrue("penalties missing in make_chat_sampler", penalties > topK);
        assertTrue("temp must follow penalties in make_chat_sampler", temp > penalties);
        assertTrue("Liquid AI LFM3 repeat_penalty (1.1f) must be configured",
                spec.contains("kChatRepeatPenalty = 1.1f"));
        assertTrue("speculative_decode.cpp must check exact-cycle repetition guard",
                spec.contains("has_verbatim_token_loop(generated)"));

        assertTrue("native.cpp sequential path must use shared hanami::make_chat_sampler(vocab)",
                nat.contains("hanami::make_chat_sampler(vocab)"));
        assertTrue("native.cpp sequential path must check exact-cycle repetition guard",
                nat.contains("hanami::has_verbatim_token_loop(generated)"));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) dir = dir.getParent();
        assertNotNull("settings.gradle", dir);
        return dir;
    }
}
