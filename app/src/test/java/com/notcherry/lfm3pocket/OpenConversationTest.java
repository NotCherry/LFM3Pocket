package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OpenConversationTest {
    @Test public void seededPreferenceBeatsTheResidentConversation() {
        OpenConversation choice = OpenConversation.choose(
                null, "tool-history-seed", "previous-live", false, "minted");
        assertEquals("tool-history-seed", choice.id);
        assertFalse(choice.confirmSwitch);
    }

    @Test public void explicitHistoryIdBeatsThePreference() {
        OpenConversation choice = OpenConversation.choose(
                "from-history", "stale-pref", "live", false, "minted");
        assertEquals("from-history", choice.id);
        assertFalse(choice.confirmSwitch);
    }

    @Test public void residentIdIsUsedOnlyWhenNothingWasStored() {
        OpenConversation choice = OpenConversation.choose(" ", "  ", "live-session", false, "minted");
        assertEquals("live-session", choice.id);
    }

    @Test public void switchingWhileGeneratingAsksBeforeDroppingTheReply() {
        OpenConversation choice = OpenConversation.choose(null, "other", "live", true, "minted");
        assertEquals("other", choice.id);
        assertTrue(choice.confirmSwitch);
    }

    @Test public void stayingOnTheGeneratingConversationDoesNotAsk() {
        OpenConversation same = OpenConversation.choose(null, "live", "live", true, "minted");
        assertEquals("live", same.id);
        assertFalse(same.confirmSwitch);
    }

    @Test public void mintsAnIdWhenNothingIsKnown() {
        assertEquals("minted", OpenConversation.choose(null, null, "", false, "minted").id);
        assertFalse(OpenConversation.choose(null, null, "", false, "minted").confirmSwitch);
    }
}
