package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.*;

public class ReasoningLevelTest {
    @Test public void promptPrefixesAndWireNamesMatchLfm3Template() {
        assertEquals("", ReasoningLevel.AUTO.promptPrefix());
        assertEquals("<think></think>", ReasoningLevel.OFF.promptPrefix());
        assertEquals("<think>\n", ReasoningLevel.LOW.promptPrefix());
        assertEquals("<think>\n", ReasoningLevel.MEDIUM.promptPrefix());
        assertEquals("<think>\n", ReasoningLevel.HIGH.promptPrefix());

        assertFalse(ReasoningLevel.AUTO.prefillsOpenThink());
        assertFalse(ReasoningLevel.OFF.prefillsOpenThink());
        assertTrue(ReasoningLevel.LOW.prefillsOpenThink());
        assertTrue(ReasoningLevel.MEDIUM.prefillsOpenThink());
        assertTrue(ReasoningLevel.HIGH.prefillsOpenThink());

        for (ReasoningLevel level : ReasoningLevel.values()) {
            assertEquals(level, ReasoningLevel.fromWire(level.wireName()));
            assertEquals(level, ReasoningLevel.fromWire(" " + level.wireName().toUpperCase(java.util.Locale.US) + " "));
        }
        assertEquals(ReasoningLevel.AUTO, ReasoningLevel.fromWire(null));
        assertEquals(ReasoningLevel.AUTO, ReasoningLevel.fromWire("unknown"));
    }

    @Test public void budgetsScaleMonotonicallyAndReserveAnswerTokens() {
        assertEquals(-1, ReasoningLevel.AUTO.effectiveBudgetTokens(512));
        assertEquals(0, ReasoningLevel.OFF.effectiveBudgetTokens(512));

        int[] maxOptions = {512, 1024, 2048};
        for (int maxTokens : maxOptions) {
            int low = ReasoningLevel.LOW.effectiveBudgetTokens(maxTokens);
            int medium = ReasoningLevel.MEDIUM.effectiveBudgetTokens(maxTokens);
            int high = ReasoningLevel.HIGH.effectiveBudgetTokens(maxTokens);
            assertTrue("low > 0 for max=" + maxTokens, low > 0);
            assertTrue("low < medium for max=" + maxTokens, low < medium);
            assertTrue("medium < high for max=" + maxTokens, medium < high);
            assertTrue("high leaves answer room for max=" + maxTokens, high < maxTokens - 64);
        }
        assertEquals(-1, ReasoningLevel.LOW.effectiveBudgetTokens(32));
    }

    @Test public void inferenceSettingsSummaryKeepsDefaultAutoFormatAndShowsExplicitLevel() {
        assertEquals(ReasoningLevel.AUTO, InferenceSettings.DEFAULT.reasoningLevel);
        assertEquals("Context 2048 • Threads 2 • Max 128 • Prompt lookup off",
                new InferenceSettings(2048, 2, 128, false, ReasoningLevel.AUTO).summary());
        assertEquals("Context 4096 • Threads 4 • Max 1024 • Prompt lookup off • Reasoning medium",
                new InferenceSettings(4096, 4, 1024, false, ReasoningLevel.MEDIUM).summary());
        assertEquals("Context 4096 • Threads 4 • Max 512 • Prompt lookup on • Reasoning off",
                new InferenceSettings(4096, 4, 512, true, ReasoningLevel.OFF).summary());
    }

    @Test public void thinkingSanitizerHandlesPrefilledOpenThinkTag() {
        ThinkingSanitizer.Cleaned streaming = ThinkingSanitizer.clean("Step 1: add 2 + 2", true);
        assertEquals("", streaming.answer);
        assertEquals("Step 1: add 2 + 2", streaming.reasoning);

        ThinkingSanitizer.Cleaned done = ThinkingSanitizer.clean("Step 1: 4.</think>\nThe answer is 4.", true);
        assertEquals("The answer is 4.", done.answer);
        assertEquals("Step 1: 4.", done.reasoning);

        ThinkingSanitizer.Cleaned immediateClose = ThinkingSanitizer.clean("</think>Direct reply", true);
        assertEquals("Direct reply", immediateClose.answer);
        assertNull(immediateClose.reasoning);

        ThinkingSanitizer.Cleaned redundantOpen = ThinkingSanitizer.clean("<think>inner</think>Final", true);
        assertEquals("Final", redundantOpen.answer);
        assertEquals("inner", redundantOpen.reasoning);
    }

    @Test public void chatSessionPrefillsThinkExtractsPersistsAndRestoresReasoning() {
        List<Runnable> workerTasks = new ArrayList<>();
        List<Runnable> uiTasks = new ArrayList<>();
        Executor worker = workerTasks::add;
        ChatSession.Dispatcher ui = (task, delay) -> uiTasks.add(task);

        List<String> prompts = new ArrayList<>();
        List<ChatSession.CompletedTurn> savedTurns = new ArrayList<>();
        ChatSnapshot[] latest = {null};

        InferenceEngine engine = new InferenceEngine() {
            @Override public long loadModel(String m, String p) { return 1L; }
            @Override public void unloadModel(long h) {}
            @Override public String generate(long h, String prompt, String media, int max, TokenSink sink) {
                prompts.add(prompt);
                if (prompt.endsWith("<|im_start|>assistant\n<think>\n")) {
                    sink.onToken("Check 17 * 3 = 51.</think>\n51");
                } else {
                    sink.onToken("Direct 51");
                }
                return null;
            }
            @Override public void stop() {}
            @Override public void resetStop() {}
        };

        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (endpoint, q) -> Collections.emptyList(),
                savedTurns::add,
                path -> {},
                (flat, status, busy) -> {},
                snap -> latest[0] = snap);

        // Medium level prefills <think>\n and extracts reasoning even though output has no opening tag.
        session.setReasoningLevel(ReasoningLevel.MEDIUM);
        assertTrue(session.submit("What is 17*3?", "", "", false, "", 512));
        while (!workerTasks.isEmpty()) workerTasks.remove(0).run();
        while (!uiTasks.isEmpty()) uiTasks.remove(0).run();

        assertEquals(1, prompts.size());
        assertTrue("Expected <think>\\n prefill", prompts.get(0).endsWith("<|im_start|>assistant\n<think>\n"));
        assertEquals("51", latest[0].turns.get(1).text);
        assertEquals("Check 17 * 3 = 51.", latest[0].turns.get(1).reasoning);
        assertEquals(1, savedTurns.size());
        assertEquals("51", savedTurns.get(0).assistantText);
        assertEquals("Check 17 * 3 = 51.", savedTurns.get(0).reasoning);

        // Off level prefills empty <think></think> block.
        session.setReasoningLevel(ReasoningLevel.OFF);
        assertTrue(session.submit("Say hi", "", "", false, "", 512));
        while (!workerTasks.isEmpty()) workerTasks.remove(0).run();
        while (!uiTasks.isEmpty()) uiTasks.remove(0).run();

        assertEquals(2, prompts.size());
        assertTrue("Expected <think></think> prefill", prompts.get(1).endsWith("<|im_start|>assistant\n<think></think>"));
        assertEquals("Direct 51", latest[0].turns.get(3).text);
        assertNull(latest[0].turns.get(3).reasoning);

        // Restoring from Room preserves saved reasoning on assistant turns.
        ChatSession restoredSession = new ChatSession(engine, worker, ui, () -> 1L,
                (endpoint, q) -> Collections.emptyList(),
                turn -> {},
                path -> {},
                (flat, status, busy) -> {},
                snap -> latest[0] = snap);
        restoredSession.restore(java.util.Arrays.asList(
                new ChatSession.RestoredMessage("user", "What is 17*3?", null, "COMPLETED", null, null),
                new ChatSession.RestoredMessage("assistant", "51", "turn-1", "COMPLETED", null, "Check 17 * 3 = 51.")
        ));
        assertEquals("51", restoredSession.snapshot().turns.get(1).text);
        assertEquals("Check 17 * 3 = 51.", restoredSession.snapshot().turns.get(1).reasoning);
    }
}
