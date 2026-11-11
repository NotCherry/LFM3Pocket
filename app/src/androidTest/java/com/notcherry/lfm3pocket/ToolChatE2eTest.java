package com.notcherry.lfm3pocket;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises validated Android-action proposals through the real chat surface. */
@RunWith(AndroidJUnit4.class)
public final class ToolChatE2eTest {
    private TestLfm3PocketApplication app;
    private UiDevice device;
    private String pkg;

    @Before public void prepare() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        app=(TestLfm3PocketApplication)context.getApplicationContext();
        app.resetTools();
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        pkg=context.getPackageName();
    }

    private ToolCoordinator.Outcome alarmProposal() {
        ToolRegistry.Parsed parsed=new ToolRegistry().parse(
                "{\"tool\":\"set_alarm\",\"arguments\":{\"hour\":7,\"minute\":30}}");
        assertTrue(parsed.isValid());
        return app.toolCoordinator().propose(parsed.request,ToolCoordinator.Source.MODEL_PROPOSAL);
    }

    private UiObject2 find(String id) {
        UiObject2 found=device.wait(Until.findObject(By.res(pkg,id)),12000);
        assertNotNull("Expected chat tool control "+id,found);
        return found;
    }

    @Test public void approvedProposalShowsArgumentsAndOnlyReportsSuccessAfterHandlerReturns() {
        ToolCoordinator.Outcome proposal=alarmProposal();
        assertEquals("PENDING_CONFIRMATION",proposal.status);
        assertEquals(0,app.toolCalls());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(proposal));
            assertTrue(find("tool_details").getText().contains("Alarm request"));
            assertTrue(find("tool_details").getText().contains("hour: 7"));
            assertTrue(find("tool_risk").getText().contains("confirmation"));
            assertTrue(find("tool_status").getText().contains("Needs your approval"));
            find("tool_approve").click();
            assertTrue(find("tool_status").getText().contains("Action complete"));
            assertTrue(find("tool_result").getText().contains("Test handler executed"));
            assertEquals(1,app.toolCalls());
            assertNull("Resolved actions must remove approval controls",device.wait(
                    Until.findObject(By.res(pkg,"tool_approve")),1000));
        }
    }

    @Test public void rejectedProposalIsVisibleAndNeverCallsHandler() {
        ToolCoordinator.Outcome proposal=alarmProposal();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(proposal));
            assertTrue(find("tool_details").getText().contains("Alarm request"));
            find("tool_reject").click();
            assertTrue(find("tool_status").getText().contains("Declined"));
            assertTrue(find("tool_result").getText().contains("Action was not executed"));
            assertEquals(0,app.toolCalls());
            assertNull(device.wait(Until.findObject(By.res(pkg,"tool_approve")),1000));
        }
    }

    @Test public void handlerFailureIsVisibleInsteadOfAFalseSuccess() {
        app.failTool(true);
        ToolCoordinator.Outcome proposal=alarmProposal();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(proposal));
            assertTrue(find("tool_status").getText().contains("Needs your approval"));
            find("tool_approve").click();
            assertTrue(find("tool_status").getText().contains("Action failed"));
            assertTrue(find("tool_result").getText().contains("Test handler failed"));
            assertEquals(1,app.toolCalls());
            assertNull(device.wait(Until.findObject(By.res(pkg,"tool_approve")),1000));
        }
    }

    @Test public void completedSafeActionShowsItsArgumentsAndResultWithoutConsentControls() {
        ToolRegistry.Parsed parsed=new ToolRegistry().parse(
                "{\"tool\":\"set_timer\",\"arguments\":{\"seconds\":30,\"label\":\"tea\"}}");
        assertTrue(parsed.isValid());
        ToolCoordinator.Outcome outcome=app.toolCoordinator().propose(
                parsed.request,ToolCoordinator.Source.USER_ACTION);
        assertEquals("TEST_PERFORMED",outcome.status);
        assertEquals("Handler must finish before its result is displayed",1,app.toolCalls());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(parsed.request,outcome));
            assertTrue(find("tool_details").getText().contains("Timer"));
            assertTrue(find("tool_details").getText().contains("seconds: 30"));
            assertTrue(find("tool_risk").getText().contains("safe action"));
            assertTrue(find("tool_status").getText().contains("Action complete"));
            assertTrue(find("tool_result").getText().contains("Test handler executed"));
            assertNull(device.wait(Until.findObject(By.res(pkg,"tool_approve")),1000));
            assertEquals(1,app.toolCalls());
        }
    }
}
