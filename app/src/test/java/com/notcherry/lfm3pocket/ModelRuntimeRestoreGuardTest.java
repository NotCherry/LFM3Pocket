package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards {@link ModelRuntime#restoreOpenConversation()} against stale async Room reads
 * overwriting a newly started or cleared conversation.
 */
public final class ModelRuntimeRestoreGuardTest {
    @Test public void allowsRestoreWhenConversationIdAndEpochsMatch() {
        assertTrue(ModelRuntime.shouldApplyRestore("conv-1", "conv-1", 3, 3, 1, 1));
    }

    @Test public void rejectsRestoreAfterSwitchingToNewConversation() {
        assertFalse(ModelRuntime.shouldApplyRestore("conv-old", "conv-new", 3, 4, 1, 1));
        assertFalse(ModelRuntime.shouldApplyRestore("conv-old", "conv-new", 3, 3, 1, 1));
    }

    @Test public void rejectsRestoreAfterClearingSameConversation() {
        // Same conversationId, but clearConversation() bumped conversationEpoch.
        assertFalse(ModelRuntime.shouldApplyRestore("conv-1", "conv-1", 3, 4, 1, 1));
    }

    @Test public void rejectsRestoreAfterSessionResetOrBlankId() {
        assertFalse(ModelRuntime.shouldApplyRestore("conv-1", "conv-1", 3, 3, 1, 2));
        assertFalse(ModelRuntime.shouldApplyRestore("", "", 3, 3, 1, 1));
        assertFalse(ModelRuntime.shouldApplyRestore(null, "conv-1", 3, 3, 1, 1));
    }
}
