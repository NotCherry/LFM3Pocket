package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ModelToolBridgeTest {
    private final ToolRegistry registry = new ToolRegistry();
    private final ToolCoordinator coordinator = new ToolCoordinator(
            request -> new AndroidActionExecutor.ActionResult(true, "OK", "done"),
            request -> true,
            System::currentTimeMillis);

    @Test public void wholeMessageJsonBecomesAPendingModelProposal() {
        String reply = "{\"tool\":\"toggle_flashlight\",\"arguments\":{\"on\":true}}";
        assertEquals(reply, ModelToolBridge.extractToolJson(reply));
        ToolCoordinator.Outcome outcome = ModelToolBridge.proposeFromAssistant(
                registry, coordinator, reply);
        assertNotNull(outcome);
        assertEquals("PENDING_CONFIRMATION", outcome.status);
        assertEquals(ToolCoordinator.Source.MODEL_PROPOSAL, outcome.proposal.source);
        assertEquals(ToolRegistry.Tool.FLASHLIGHT, outcome.proposal.request.tool);
    }

    @Test public void fencedJsonBlockIsAcceptedAndProseAroundAFragmentIsNot() {
        String fenced = "Sure.\n```json\n{\"tool\":\"set_timer\",\"arguments\":{\"seconds\":60,\"label\":\"tea\"}}\n```\n";
        assertNotNull(ModelToolBridge.extractToolJson(fenced));
        assertNull(ModelToolBridge.extractToolJson(
                "Please run {\"tool\":\"toggle_flashlight\",\"arguments\":{\"on\":true}} now."));
        assertNull(ModelToolBridge.extractToolJson("No tools here."));
    }

    @Test public void unknownAndUntrustedPathsNeverExecute() {
        assertNull(ModelToolBridge.proposeFromAssistant(registry, coordinator,
                "{\"tool\":\"run_shell\",\"arguments\":{\"command\":\"id\"}}"));
        ToolCoordinator.Outcome image = coordinator.propose(
                registry.parse("{\"tool\":\"toggle_flashlight\",\"arguments\":{\"on\":true}}").request,
                ToolCoordinator.Source.IMAGE);
        assertEquals("UNTRUSTED_SOURCE", image.status);
        assertTrue(image.result.message.contains("Untrusted"));
    }
}
