package com.notcherry.lfm3pocket;

import static org.junit.Assert.*;

import android.content.ComponentCallbacks2;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression guards for multi-turn KV prefix reuse, hybrid LFM2/LFM3 kv_token_count/benchmark,
 * zero-overhead deferred literal grammar, and keep-loaded memory trim resilience.
 */
public final class KvCachePerformanceAndTrimRegressionTest {
    private static final class Queue implements Executor, ChatSession.Dispatcher {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public void post(Runnable task, long delay) { tasks.add(task); }
        void drain() { while (!tasks.isEmpty()) tasks.remove(0).run(); }
    }

    private static final class CapturingEngine implements InferenceEngine {
        final Deque<String> replies = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        final List<String> grammars = new ArrayList<>();
        @Override public long loadModel(String m, String p) { return 1L; }
        @Override public void unloadModel(long h) {}
        @Override public String generate(long h, String p, String media, int max, TokenSink sink) {
            return generateWithMediaAndGrammar(h, p, new String[0], max, null, null, sink);
        }
        @Override public String generateWithMediaAndGrammar(long h, String p, String[] media, int max,
                                                            String grammar, String trigger, TokenSink sink) {
            prompts.add(p);
            grammars.add(grammar);
            sink.onProgress("tokenize", 100, 4096);
            sink.onProgress("prefill", 100, 100);
            sink.onProgress("decode", 0, max);
            String reply = replies.isEmpty() ? "OK" : replies.removeFirst();
            for (int i = 0; i < reply.length(); i++) {
                sink.onToken(String.valueOf(reply.charAt(i)));
            }
            return null;
        }
        @Override public void stop() {}
        @Override public void resetStop() {}
    }

    private static ChatSession.ToolHost offlineToolHost() {
        return new ChatSession.ToolHost() {
            @Override public LfmToolCatalog.Capabilities capabilities(boolean useSearch, String ep) {
                return LfmToolCatalog.Capabilities.offlineDefaults();
            }
            @Override public Locale locale() { return Locale.US; }
            @Override public ToolRegistry registry() { return new ToolRegistry(); }
            @Override public boolean canAutoExecute(ToolRegistry.Request request) { return false; }
            @Override public AndroidActionExecutor.ActionResult executeReadOnly(ToolRegistry.Request request) {
                return new AndroidActionExecutor.ActionResult(false, "NOOP", "");
            }
            @Override public void proposeTool(ToolRegistry.Request request) {}
        };
    }

    @Test public void multiTurnToolPromptsStrictlyExtendPreviousPromptAndRawReply() {
        Queue worker = new Queue(), ui = new Queue();
        CapturingEngine engine = new CapturingEngine();
        // Leading space & trailing newline would break KV match if ThinkingSanitizer.trim() leaked into prompt.
        String rawReply1 = " Sure! I can help with that.\n";
        String rawReply2 = "Here is the second answer.";
        engine.replies.add(rawReply1);
        engine.replies.add(rawReply2);
        engine.replies.add("Third answer.");

        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (ep, q) -> Collections.emptyList(), (t, s, g) -> {});
        session.setToolHost(offlineToolHost());

        assertTrue(session.submit("Set a timer for 5 minutes", "", "", false, "", 128));
        worker.drain(); ui.drain();
        assertTrue(session.submit("Follow up question", "", "", false, "", 128));
        worker.drain(); ui.drain();
        assertTrue(session.submit("Third question", "", "", false, "", 128));
        worker.drain(); ui.drain();

        assertEquals(3, engine.prompts.size());
        String p1 = engine.prompts.get(0);
        String p2 = engine.prompts.get(1);
        String p3 = engine.prompts.get(2);

        assertTrue("Tool request must engage tool catalog", p1.contains("<tools>"));
        assertFalse("Offline tool context must not emit web_search=off refusal cue",
                p1.contains("web_search=off"));
        assertNotNull("Tool turn should pass lazy grammar", engine.grammars.get(0));

        // Turn 2 must strictly extend Turn 1's prompt + exact raw reply so native KV reuse matches 100%.
        assertTrue("Turn 2 prompt must preserve Turn 1's frozen [Context: ...] and raw reply.\nP1:\n"
                        + p1 + "\nP2:\n" + p2,
                p2.startsWith(p1 + rawReply1 + "<|im_end|>\n"));
        assertTrue("Turn 3 prompt must preserve Turn 2's full prefix.\nP2:\n" + p2 + "\nP3:\n" + p3,
                p3.startsWith(p2 + rawReply2 + "<|im_end|>\n"));

        // Visible UI snapshot must still show the clean trimmed reply.
        assertEquals("Sure! I can help with that.", session.snapshot().turns.get(1).text);
    }

    @Test public void casualOfflineChatSkipsToolPromptAndGrammarToEnableSpeculativeDecoding() {
        Queue worker = new Queue(), ui = new Queue();
        CapturingEngine engine = new CapturingEngine();
        String raw1 = "Hi! Nice to meet you too.";
        String raw2 = "Crispy fried chicken is great! Here is a simple recipe.";
        String raw3 = "To bake an apple pie, start with sliced apples and pastry dough.";
        engine.replies.add(raw1);
        engine.replies.add(raw2);
        engine.replies.add(raw3);

        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (ep, q) -> Collections.emptyList(), (t, s, g) -> {});
        session.setToolHost(offlineToolHost());

        assertTrue(session.submit("ok hi nice to meet you", "", "", false, "", 128));
        worker.drain(); ui.drain();
        assertTrue(session.submit("so how about fired cholixken", "", "", false, "", 128));
        worker.drain(); ui.drain();
        assertTrue(session.submit("ok how to bake an apple pie", "", "", false, "", 128));
        worker.drain(); ui.drain();

        assertEquals(3, engine.prompts.size());
        for (int i = 0; i < 3; i++) {
            String p = engine.prompts.get(i);
            assertFalse("Casual turn " + i + " must not inject 500-token <tools> catalog",
                    p.contains("<tools>"));
            assertFalse("Casual turn " + i + " must not inject [Context:] metadata",
                    p.contains("[Context:"));
            assertNull("Casual turn " + i + " must pass null grammar so ngram-mod speculative decoding stays active",
                    engine.grammars.get(i));
        }
        assertTrue("Turn 2 must strictly extend Turn 1 for 100% KV reuse",
                engine.prompts.get(1).startsWith(engine.prompts.get(0) + raw1 + "<|im_end|>\n"));
        assertTrue("Turn 3 must strictly extend Turn 2 for 100% KV reuse",
                engine.prompts.get(2).startsWith(engine.prompts.get(1) + raw2 + "<|im_end|>\n"));
    }

    @Test public void toolModeIntentGateAndTurnContextLineBehaveCleanly() {
        LfmToolCatalog.Capabilities offline = LfmToolCatalog.Capabilities.offlineDefaults();
        LfmToolCatalog.Capabilities workspace = new LfmToolCatalog.Capabilities(
                false, false, false, false, "", false, true, "Vault");
        LfmToolCatalog.Capabilities webOn = new LfmToolCatalog.Capabilities(
                true, false, false, false, "", false);

        assertFalse(LfmToolCatalog.shouldEngageToolMode("ok hi nice to meet you", false, offline));
        assertFalse(LfmToolCatalog.shouldEngageToolMode("so how about fired cholixken", false, offline));
        assertFalse(LfmToolCatalog.shouldEngageToolMode("how to make one", false, offline));
        assertFalse(LfmToolCatalog.shouldEngageToolMode("ok how to bake an apple pie", false, offline));
        assertFalse(LfmToolCatalog.shouldEngageToolMode("jak upiec szarlotkę", false, offline));
        assertFalse(LfmToolCatalog.shouldEngageToolMode("explain how speculative decoding works", false, offline));

        assertTrue(LfmToolCatalog.shouldEngageToolMode("Set a timer for 5 minutes", false, offline));
        assertTrue(LfmToolCatalog.shouldEngageToolMode("Ustaw minutnik na 5 minut", false, offline));
        assertTrue(LfmToolCatalog.shouldEngageToolMode("Schedule Dentist tomorrow at 15:00", false, offline));
        assertTrue(LfmToolCatalog.shouldEngageToolMode("What time is it?", false, offline));
        assertTrue(LfmToolCatalog.shouldEngageToolMode("Read my todo.md", false, workspace));
        assertTrue(LfmToolCatalog.shouldEngageToolMode("Latest news today", true, offline));
        assertTrue(LfmToolCatalog.shouldEngageToolMode("Latest news today", false, webOn));

        String offlineCtx = LfmToolCatalog.turnContextLine(java.time.ZonedDateTime.now(), offline);
        assertFalse("Offline context line must omit web_search=off", offlineCtx.contains("web_search=off"));
        String onlineCtx = LfmToolCatalog.turnContextLine(java.time.ZonedDateTime.now(), webOn);
        assertTrue("Online context line must include web_search=on", onlineCtx.contains("web_search=on"));
    }

    @Test public void modelRuntimeNeverPersistsTransientFdPath() {
        assertFalse(ModelRuntime.shouldPersistModelPath(null));
        assertFalse(ModelRuntime.shouldPersistModelPath(""));
        assertFalse(ModelRuntime.shouldPersistModelPath("/proc/self/fd/74"));
        assertTrue(ModelRuntime.shouldPersistModelPath(
                "/data/user/0/com.notcherry.lfm3pocket/files/models/lfm3.gguf"));
        assertTrue(ModelRuntime.shouldPersistModelPath(
                "content://com.android.externalstorage.documents/document/primary:lfm3.gguf"));
    }

    @Test public void thinkingBlocksStayExcludedFromSubsequentTurnPrompts() {
        Queue worker = new Queue(), ui = new Queue();
        CapturingEngine engine = new CapturingEngine();
        engine.replies.add("<think>private chain of thought</think>Clean visible answer");
        engine.replies.add("Second answer");

        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (ep, q) -> Collections.emptyList(), (t, s, g) -> {});
        session.setToolHost(offlineToolHost());

        assertTrue(session.submit("First", "", "", false, "", 128));
        worker.drain(); ui.drain();
        assertTrue(session.submit("Second", "", "", false, "", 128));
        worker.drain(); ui.drain();

        String p2 = engine.prompts.get(1);
        assertFalse("Turn 2 prompt must not re-inject <think> tags", p2.contains("<think>"));
        assertFalse("Turn 2 prompt must not re-inject private reasoning", p2.contains("private chain of thought"));
        assertTrue("Turn 2 prompt must include cleaned answer", p2.contains("Clean visible answer"));
    }

    @Test public void progressSinkThrottlesResourceSamplingDuringTokenStreaming() {
        Queue worker = new Queue(), ui = new Queue();
        CapturingEngine engine = new CapturingEngine();
        // 60 single-char tokens streamed in one turn.
        StringBuilder longReply = new StringBuilder();
        for (int i = 0; i < 60; i++) longReply.append('a');
        engine.replies.add(longReply.toString());

        AtomicInteger sampleCalls = new AtomicInteger();
        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (ep, q) -> Collections.emptyList(), (t, s, g) -> {});
        session.setTelemetry(new ProcessingTelemetry(), () -> {
            sampleCalls.incrementAndGet();
            return "RAM 3.0 GB free";
        });

        assertTrue(session.submit("Stream test", "", "", false, "", 128));
        worker.drain(); ui.drain();

        // Only stage transitions (tokenize, prefill start, decode start, finishSummary) should sample,
        // NOT every 12 tokens during tight decode.
        assertTrue("Expected <= 5 resource samples for 60-token turn, got " + sampleCalls.get(),
                sampleCalls.get() <= 5);
    }

    @Test public void routineAppSwitchesDoNotWipeKvAndKeepLoadedUsesTwoStageTrim() {
        // Routine app switch / lock screen / background LRU levels must NEVER wipe KV cache.
        assertFalse("TRIM_MEMORY_UI_HIDDEN must not wipe KV",
                ModelRuntime.shouldReleaseKvForTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN));
        assertFalse("TRIM_MEMORY_BACKGROUND must not wipe KV",
                ModelRuntime.shouldReleaseKvForTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND));
        assertFalse("TRIM_MEMORY_RUNNING_LOW must not wipe KV",
                ModelRuntime.shouldReleaseKvForTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW));

        // Real memory pressure releases KV.
        assertTrue(ModelRuntime.shouldReleaseKvForTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL));
        assertTrue(ModelRuntime.shouldReleaseKvForTrim(ComponentCallbacks2.TRIM_MEMORY_MODERATE));
        assertTrue(ModelRuntime.shouldReleaseKvForTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE));

        // keepLoaded=true: first RUNNING_CRITICAL sheds KV without immediately unloading weights.
        assertFalse("First RUNNING_CRITICAL with keepLoaded=true should shed KV first",
                ModelRuntime.shouldEscalateTrimToUnload(
                        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                        true, true, false, 1024L, 100_000L, 0L));
        // OEM duplicate RUNNING_CRITICAL burst (<3s apart) with ample RAM does not immediately thrash-unload.
        assertFalse("Duplicate RUNNING_CRITICAL burst within 3s with ample RAM should be debounced",
                ModelRuntime.shouldEscalateTrimToUnload(
                        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                        true, true, false, 1182L, 100_950L, 100_000L));
        // Second RUNNING_CRITICAL after debounce window (within 60s) escalates to unload.
        assertTrue("Repeated RUNNING_CRITICAL within 60s should escalate to unload",
                ModelRuntime.shouldEscalateTrimToUnload(
                        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                        true, true, false, 1024L, 120_000L, 100_000L));
        // Kernel lowMemory always escalates.
        assertTrue("Kernel lowMemory must escalate to unload",
                ModelRuntime.shouldEscalateTrimToUnload(
                        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                        true, true, true, 200L, 100_000L, 0L));
        // OEM background TRIM_MEMORY_COMPLETE while FGS active with ample free RAM keeps weights.
        assertFalse("OEM TRIM_MEMORY_COMPLETE with active FGS and >=512MB free should keep weights",
                ModelRuntime.shouldEscalateTrimToUnload(
                        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                        true, true, false, 1200L, 100_000L, 0L));
        // TRIM_MEMORY_COMPLETE without FGS or with low free RAM unloads.
        assertTrue("TRIM_MEMORY_COMPLETE without FGS must unload",
                ModelRuntime.shouldEscalateTrimToUnload(
                        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                        true, false, false, 1200L, 100_000L, 0L));
        assertTrue("TRIM_MEMORY_COMPLETE with <512MB free must unload",
                ModelRuntime.shouldEscalateTrimToUnload(
                        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                        true, true, false, 300L, 100_000L, 0L));
        // keepLoaded=false always escalates on critical/complete.
        assertTrue(ModelRuntime.shouldEscalateTrimToUnload(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                false, true, false, 2000L, 100_000L, 0L));
    }

    @Test public void nativeHybridKvCountCheckpointsDeferredGrammarAndBenchmarkContractsHold() throws Exception {
        Path root = repoRoot();
        String spec = new String(Files.readAllBytes(root.resolve("app/src/main/cpp/speculative_decode.cpp")),
                StandardCharsets.UTF_8);
        String nat = new String(Files.readAllBytes(root.resolve("app/src/main/cpp/native.cpp")),
                StandardCharsets.UTF_8);

        int kvFnStart = spec.indexOf("size_t kv_token_count(");
        assertTrue("kv_token_count missing", kvFnStart >= 0);
        int kvFnEnd = spec.indexOf("\n}\n", kvFnStart);
        String kvBody = spec.substring(kvFnStart, kvFnEnd);
        assertTrue("kv_token_count must use mx + 1 for hybrid LFM2/LFM3 models",
                kvBody.contains("mx + 1"));
        assertFalse("kv_token_count must not use mx - mn + 1 (returns 1 on hybrid/recurrent models)",
                kvBody.contains("mx - mn + 1"));

        assertTrue("native.cpp must keep full physical ubatch size for fast prefill",
                nat.contains("ctx_params.n_ubatch = batch;"));
        assertTrue("native.cpp must guard partial checkpoints against SWA models like LFM3",
                nat.contains("llama_model_n_swa"));
        assertTrue("native.cpp must snapshot hybrid partial checkpoints",
                nat.contains("LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY"));
        assertTrue("native.cpp must defer literal lazy grammar until trigger appears",
                nat.contains("extract_literal_trigger"));
        assertTrue("runBenchmarkUtf8 must enable recurrent rollback snapshots for ngram-mod",
                nat.contains("hanami::kNgramMaxDraft"));
        assertTrue("runBenchmarkUtf8 must keep pp/tg sequential output when ngram-mod is unavailable",
                nat.contains("tg (ngram-mod): unavailable"));
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) dir = dir.getParent();
        assertNotNull("settings.gradle", dir);
        return dir;
    }
}
