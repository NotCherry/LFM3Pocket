package com.notcherry.lfm3pocket;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HistoryMediaCleanupTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void deletesAbandonedVoiceActionRecordingsAfterProcessDeath() throws Exception {
        File cache = temp.newFolder("cache");
        File voiceActions = new File(cache, "voice-actions");
        assertTrue(voiceActions.mkdirs());
        File wav = new File(voiceActions, "voice-123.wav");
        File partial = new File(voiceActions, "voice-456.partial");
        File keep = new File(voiceActions, "keep.txt");
        assertTrue(wav.createNewFile());
        assertTrue(partial.createNewFile());
        assertTrue(keep.createNewFile());

        assertTrue(HistoryMediaCleanup.deleteTemporaryAttachments(cache));

        assertFalse(wav.exists());
        assertFalse(partial.exists());
        assertTrue("unrelated files must not be deleted", keep.exists());
        assertTrue("directory with remaining files must stay", voiceActions.isDirectory());
    }

    @Test public void removesEmptyVoiceDirectories() throws Exception {
        File cache = temp.newFolder("cache2");
        File recordings = new File(cache, "recordings");
        File voiceActions = new File(cache, "voice-actions");
        assertTrue(recordings.mkdirs());
        assertTrue(voiceActions.mkdirs());
        assertTrue(new File(recordings, "voice-a.partial").createNewFile());
        assertTrue(new File(voiceActions, "voice-b.wav").createNewFile());

        assertTrue(HistoryMediaCleanup.deleteTemporaryAttachments(cache));

        assertFalse(recordings.exists());
        assertFalse(voiceActions.exists());
    }
}
