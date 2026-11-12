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
import java.util.UUID;
import static org.junit.Assert.*;

/** Adversarial tool requests stay inert until every validation, origin, policy, and consent gate passes. */
@RunWith(AndroidJUnit4.class)
public final class AdversarialAndroidActionE2eTest {
    private TestLfm3PocketApplication app;
    private UiDevice device;
    private String pkg;

    @Before public void prepare() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        app=(TestLfm3PocketApplication)context.getApplicationContext();
        app.resetTools();
        assertTrue(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit()
                .putString("history_conversation_id","adversarial-"+UUID.randomUUID()).commit());
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        pkg=context.getPackageName();
    }

    private UiObject2 find(String id) {
        UiObject2 found=device.wait(Until.findObject(By.res(pkg,id)),12000);
        assertNotNull("Expected chat tool control "+id,found);
        return found;
    }

    private ToolRegistry.Parsed parse(String json) { return new ToolRegistry().parse(json); }

    private ToolRegistry.Request flashlightRequest() {
        ToolRegistry.Parsed parsed=parse("{\"tool\":\"toggle_flashlight\",\"arguments\":{\"on\":true}}");
        assertTrue(parsed.status,parsed.isValid());
        return parsed.request;
    }

    @Test public void unknownAndInvalidStructuredCallsNeverReachAndroidHandler() {
        ToolRegistry.Parsed unknown=parse("{\"tool\":\"run_shell\",\"arguments\":{\"command\":\"id\"}}");
        ToolRegistry.Parsed invalid=parse("{\"tool\":\"toggle_flashlight\",\"arguments\":{\"on\":true,\"command\":\"id\"}}");
        assertEquals("UNKNOWN_TOOL",unknown.status);
        assertEquals("INVALID_ARGUMENTS",invalid.status);
        assertNull(unknown.request);
        assertNull(invalid.request);
        assertEquals("Rejected JSON data has no path to Android action execution",0,app.toolCalls());
    }

    @Test public void promptInjectionAttributedToImageIsBlockedInChatWithoutSideEffects() {
        // Simulate OCR/model-visible image text requesting a valid tool; origin remains IMAGE.
        ToolRegistry.Request request=flashlightRequest();
        ToolCoordinator.Outcome blocked=app.toolCoordinator().propose(
                request,ToolCoordinator.Source.IMAGE);
        assertEquals("UNTRUSTED_SOURCE",blocked.status);
        assertEquals(0,app.toolCalls());

        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(request,blocked));
            assertTrue(find("tool_details").getText().contains("Flashlight"));
            assertTrue(find("tool_status").getText().contains("Request blocked"));
            assertTrue(find("tool_result").getText().contains("Untrusted content cannot authorize Android actions"));
            assertNull(device.wait(Until.findObject(By.res(pkg,"tool_approve")),1000));
            assertEquals(0,app.toolCalls());
        }
    }

    @Test public void permissionDenialIsShownInChatAndDoesNotInvokeHandler() {
        app.denyToolPermission(true);
        ToolRegistry.Request request=flashlightRequest();
        ToolCoordinator.Outcome denied=app.toolCoordinator().propose(
                request,ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PERMISSION_DENIED",denied.status);
        assertEquals(0,app.toolCalls());

        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(request,denied));
            assertTrue(find("tool_details").getText().contains("Flashlight"));
            assertTrue(find("tool_status").getText().contains("Permission denied"));
            assertTrue(find("tool_result").getText().contains("Grant Camera permission"));
            assertNull(device.wait(Until.findObject(By.res(pkg,"tool_approve")),1000));
            assertEquals(0,app.toolCalls());
        }
    }

    @Test public void consentRejectionLeavesSensitiveActionInert() {
        ToolRegistry.Parsed parsed=parse("{\"tool\":\"set_alarm\",\"arguments\":{\"hour\":7,\"minute\":30}}");
        assertTrue(parsed.status,parsed.isValid());
        ToolCoordinator.Outcome proposal=app.toolCoordinator().propose(
                parsed.request,ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION",proposal.status);
        assertEquals(0,app.toolCalls());

        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->activity.presentToolOutcome(proposal));
            assertTrue(find("tool_details").getText().contains("Alarm request"));
            find("tool_reject").click();
            assertTrue(find("tool_status").getText().contains("Declined"));
            assertTrue(find("tool_result").getText().contains("Action was not executed"));
            assertEquals(0,app.toolCalls());
        }
    }
}
