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
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

/** Exercises validated Android-action proposals through the real chat surface. */
@RunWith(AndroidJUnit4.class)
public final class ToolChatE2eTest {
    private TestLfm3PocketApplication app;
    private UiDevice device;
    private String pkg;
    private String historyConversationId;

    @Before public void prepare() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        app=(TestLfm3PocketApplication)context.getApplicationContext();
        app.resetTools();
        historyConversationId="tool-history-"+UUID.randomUUID();
        assertTrue(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit()
                .putString("history_conversation_id",historyConversationId).commit());
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
        ToolRegistry.Parsed parsed=new ToolRegistry().parse(
                "{\"tool\":\"set_volume\",\"arguments\":{\"stream\":\"music\",\"percent\":40}}");
        assertTrue(parsed.isValid());
        ToolCoordinator.Outcome proposal=app.toolCoordinator().propose(
                parsed.request,ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION",proposal.status);
        assertEquals(0,app.toolCalls());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(proposal));
            assertTrue(find("tool_details").getText().contains("Audio volume"));
            assertTrue(find("tool_details").getText().contains("percent: 40"));
            assertTrue(find("tool_risk").getText().contains("safe action"));
            assertTrue(find("tool_status").getText().contains("Needs your approval"));
            find("tool_approve").click();
            assertTrue(find("tool_status").getText().contains("Action complete"));
            assertTrue(find("tool_result").getText().contains("Test handler executed"));
            assertEquals(1,app.toolCalls());
            assertNull("Resolved actions must remove approval controls",device.wait(
                    Until.findObject(By.res(pkg,"tool_approve")),1000));
        }
        List<HistoryToolOutcome> saved=HistoryDatabase.get(
                InstrumentationRegistry.getInstrumentation().getTargetContext()).history().toolOutcomes(historyConversationId);
        assertEquals("Resolved chat action should be persisted once",1,saved.size());
        assertEquals("TEST_PERFORMED",saved.get(0).getStatus());
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
                "{\"tool\":\"set_volume\",\"arguments\":{\"stream\":\"music\",\"percent\":35}}");
        assertTrue(parsed.isValid());
        ToolCoordinator.Outcome outcome=app.toolCoordinator().propose(
                parsed.request,ToolCoordinator.Source.USER_ACTION);
        assertEquals("TEST_PERFORMED",outcome.status);
        assertEquals("Handler must finish before its result is displayed",1,app.toolCalls());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(parsed.request,outcome));
            assertTrue(find("tool_details").getText().contains("Audio volume"));
            assertTrue(find("tool_details").getText().contains("stream: \"music\""));
            assertTrue(find("tool_details").getText().contains("percent: 35"));
            assertTrue(find("tool_risk").getText().contains("safe action"));
            assertTrue(find("tool_status").getText().contains("Action complete"));
            assertTrue(find("tool_result").getText().contains("Test handler executed"));
            assertNull(device.wait(Until.findObject(By.res(pkg,"tool_approve")),1000));
            assertEquals(1,app.toolCalls());
        }
    }

    @Test public void timerProposalRequiresChatApprovalAndShowsOpenedRequestWithoutClaimingCreation() {
        ToolRegistry.Parsed parsed=new ToolRegistry().parse(
                "{\"tool\":\"set_timer\",\"arguments\":{\"seconds\":75,\"label\":\"Tea break\"}}");
        assertTrue(parsed.isValid());
        ToolCoordinator.Outcome proposal=app.toolCoordinator().propose(
                parsed.request,ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION",proposal.status);
        assertEquals(0,app.toolCalls());

        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(proposal));
            assertTrue(find("tool_details").getText().contains("Timer request"));
            assertTrue(find("tool_details").getText().contains("seconds: 75"));
            assertTrue(find("tool_details").getText().contains("Tea break"));
            assertTrue(find("tool_status").getText().contains("Needs your approval"));
            find("tool_approve").click();
            assertTrue(find("tool_status").getText().contains("Action complete"));
            assertTrue(find("tool_result").getText().contains("Timer request sent"));
            assertTrue(find("tool_result").getText().contains("verify it started"));
            assertFalse(find("tool_result").getText().toLowerCase().contains("timer created"));
            assertEquals("A single approval invokes exactly one validated timer request",1,app.toolCalls());
            assertTrue(app.lastToolRequest() instanceof ToolRegistry.TimerRequest);
            ToolRegistry.TimerRequest request=(ToolRegistry.TimerRequest)app.lastToolRequest();
            assertEquals(75,request.seconds);
            assertEquals("Tea break",request.label);
            assertNull(device.wait(Until.findObject(By.res(pkg,"tool_approve")),1000));
        }
    }

    @Test public void persistentAlarmProposalNeedsAppConfirmationAndShowsExactClockRequest() {
        ToolRegistry.Parsed parsed=new ToolRegistry().parse(
                "{\"tool\":\"set_alarm\",\"arguments\":{\"hour\":8,\"minute\":15,\"label\":\"Standup\"}}");
        assertTrue(parsed.isValid());
        ToolCoordinator.Outcome proposal=app.toolCoordinator().propose(
                parsed.request,ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION",proposal.status);
        assertEquals(0,app.toolCalls());

        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(proposal));
            assertTrue(find("tool_details").getText().contains("Alarm request"));
            assertTrue(find("tool_details").getText().contains("hour: 8"));
            assertTrue(find("tool_details").getText().contains("minute: 15"));
            assertTrue(find("tool_details").getText().contains("Standup"));
            assertTrue(find("tool_risk").getText().contains("confirmation"));
            assertTrue(find("tool_status").getText().contains("Needs your approval"));
            find("tool_approve").click();
            assertTrue(find("tool_status").getText().contains("Action complete"));
            assertTrue(find("tool_result").getText().contains("Alarm request sent"));
            assertTrue(find("tool_result").getText().contains("08:15"));
            assertTrue(find("tool_result").getText().contains("Check Clock"));
            assertFalse(find("tool_result").getText().toLowerCase().contains("alarm created"));
            assertEquals("Approval invokes exactly one validated alarm request",1,app.toolCalls());
            assertTrue(app.lastToolRequest() instanceof ToolRegistry.AlarmRequest);
            ToolRegistry.AlarmRequest request=(ToolRegistry.AlarmRequest)app.lastToolRequest();
            assertEquals(8,request.hour);
            assertEquals(15,request.minute);
            assertEquals("Standup",request.label);
            assertNull(device.wait(Until.findObject(By.res(pkg,"tool_approve")),1000));
        }
        List<HistoryToolOutcome> saved=HistoryDatabase.get(
                InstrumentationRegistry.getInstrumentation().getTargetContext()).history().toolOutcomes(historyConversationId);
        assertEquals(1,saved.size());
        assertEquals("set_alarm",saved.get(0).getTool());
        assertEquals("TEST_ALARM_REQUEST_OPENED",saved.get(0).getStatus());
    }

    @Test public void modelInstalledAppProposalNeedsChatApprovalAndNamesTheExactPackage() {
        ToolRegistry.Parsed parsed=new ToolRegistry().parse(
                "{\"tool\":\"open_application\",\"arguments\":{\"package\":\"com.example.notes\"}}");
        assertTrue(parsed.isValid());
        ToolCoordinator.Outcome proposal=app.toolCoordinator().propose(
                parsed.request,ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION",proposal.status);
        assertEquals(0,app.toolCalls());

        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(proposal));
            assertTrue(find("tool_details").getText().contains("Open app"));
            assertTrue(find("tool_details").getText().contains("com.example.notes"));
            assertTrue(find("tool_status").getText().contains("Needs your approval"));
            find("tool_approve").click();
            assertTrue(find("tool_status").getText().contains("Action complete"));
            assertTrue(find("tool_result").getText().contains("Test handler executed"));
            assertEquals(1,app.toolCalls());
            assertTrue(app.lastToolRequest() instanceof ToolRegistry.ApplicationRequest);
            assertEquals("com.example.notes",((ToolRegistry.ApplicationRequest)app.lastToolRequest()).packageName);
            assertNull(device.wait(Until.findObject(By.res(pkg,"tool_approve")),1000));
        }
    }

    @Test public void rejectedInstalledAppProposalDoesNotCallTheLauncherHandler() {
        ToolRegistry.Parsed parsed=new ToolRegistry().parse(
                "{\"tool\":\"open_application\",\"arguments\":{\"package\":\"com.example.notes\"}}");
        assertTrue(parsed.isValid());
        ToolCoordinator.Outcome proposal=app.toolCoordinator().propose(
                parsed.request,ToolCoordinator.Source.MODEL_PROPOSAL);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(proposal));
            assertTrue(find("tool_details").getText().contains("com.example.notes"));
            find("tool_reject").click();
            assertTrue(find("tool_status").getText().contains("Declined"));
            assertTrue(find("tool_result").getText().contains("Action was not executed"));
            assertEquals(0,app.toolCalls());
        }
    }

    @Test public void flashlightProposalUsesChatApprovalAndShowsTheValidatedResult() {
        ToolRegistry.Parsed parsed=new ToolRegistry().parse(
                "{\"tool\":\"toggle_flashlight\",\"arguments\":{\"on\":true}}");
        assertTrue(parsed.isValid());
        ToolCoordinator.Outcome proposal=app.toolCoordinator().propose(
                parsed.request,ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION",proposal.status);
        assertEquals(0,app.toolCalls());

        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(proposal));
            assertTrue(find("tool_details").getText().contains("Flashlight"));
            assertTrue(find("tool_details").getText().contains("on: true"));
            assertTrue(find("tool_risk").getText().contains("safe action"));
            assertTrue(find("tool_status").getText().contains("Needs your approval"));
            find("tool_approve").click();
            assertTrue(find("tool_status").getText().contains("Action complete"));
            assertTrue(find("tool_result").getText().contains("Test handler executed"));
            assertEquals("Approval should invoke the installed fake handler exactly once",1,app.toolCalls());
            assertNull(device.wait(Until.findObject(By.res(pkg,"tool_approve")),1000));
        }
        List<HistoryToolOutcome> saved=HistoryDatabase.get(
                InstrumentationRegistry.getInstrumentation().getTargetContext()).history().toolOutcomes(historyConversationId);
        assertEquals(1,saved.size());
        assertEquals("toggle_flashlight",saved.get(0).getTool());
        assertEquals("TEST_PERFORMED",saved.get(0).getStatus());
    }

    @Test public void safeMusicVolumeProposalShowsExactStreamAndPercentThroughChatApproval() {
        ToolRegistry.Parsed parsed=new ToolRegistry().parse(
                "{\"tool\":\"set_volume\",\"arguments\":{\"stream\":\"music\",\"percent\":35}}");
        assertTrue(parsed.isValid());
        ToolCoordinator.Outcome proposal=app.toolCoordinator().propose(
                parsed.request,ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION",proposal.status);
        assertEquals(0,app.toolCalls());

        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(proposal));
            assertTrue(find("tool_details").getText().contains("Audio volume"));
            assertTrue(find("tool_details").getText().contains("stream: \"music\""));
            assertTrue(find("tool_details").getText().contains("percent: 35"));
            assertTrue(find("tool_risk").getText().contains("safe action"));
            assertTrue(find("tool_status").getText().contains("Needs your approval"));
            find("tool_approve").click();
            assertTrue(find("tool_status").getText().contains("Action complete"));
            assertTrue(find("tool_result").getText().contains("Test handler executed"));
            assertEquals(1,app.toolCalls());
            assertNull(device.wait(Until.findObject(By.res(pkg,"tool_approve")),1000));
        }
        List<HistoryToolOutcome> saved=HistoryDatabase.get(
                InstrumentationRegistry.getInstrumentation().getTargetContext()).history().toolOutcomes(historyConversationId);
        assertEquals(1,saved.size());
        assertEquals("set_volume",saved.get(0).getTool());
        assertEquals("TEST_PERFORMED",saved.get(0).getStatus());
    }
}
