package com.notcherry.lfm3pocket;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticsRecorderTest {
    @Test public void marksStayLocalAndRenderNeverClaimsUpload() {
        DiagnosticsRecorder recorder = new DiagnosticsRecorder();
        recorder.mark("model_load", "ok");
        recorder.mark("first_token");
        String report = recorder.render();
        assertTrue(report.contains("never uploaded"));
        assertTrue(report.contains("model_load"));
        assertTrue(report.contains("first_token"));
        assertEquals(2, recorder.marks().size());
        assertEquals("model_load", recorder.marks().get(0).name);
        assertTrue(recorder.marks().get(0).elapsedMs >= 0);
        recorder.clear();
        assertTrue(recorder.marks().isEmpty());
        assertTrue(recorder.render().contains("no marks yet"));
        assertTrue(recorder.render().contains("never uploaded"));
    }
}
