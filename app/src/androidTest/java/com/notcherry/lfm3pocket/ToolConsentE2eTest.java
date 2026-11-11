package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ToolConsentE2eTest {
    private Context context;private TestLfm3PocketApplication app;private UiDevice device;private String pkg;
    @Before public void setup(){context=InstrumentationRegistry.getInstrumentation().getTargetContext();app=(TestLfm3PocketApplication)context.getApplicationContext();app.resetTools();device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());pkg=context.getPackageName();}
    private ToolCoordinator.Outcome proposal(){return app.toolCoordinator().propose(new ToolRegistry().parse("{\"tool\":\"set_alarm\",\"arguments\":{\"hour\":7,\"minute\":30}}").request,ToolCoordinator.Source.MODEL_PROPOSAL);}
    private ActivityScenario<ToolConfirmationActivity> open(String id){return ActivityScenario.launch(new Intent(context,ToolConfirmationActivity.class).putExtra("proposal_id",id));}
    private void click(String id){UiObject2 button=device.wait(Until.findObject(By.res(pkg,id).enabled(true)),12000);assertNotNull(id,button);button.click();}
    private void status(String text){assertNotNull(device.wait(Until.findObject(By.res(pkg,"tool_status").textContains(text)),12000));}
    @Test public void explicitApprovalRunsOnceAndDisablesRepeatedApproval(){
        ToolCoordinator.Outcome p=proposal();assertEquals(0,app.toolCalls());
        try(ActivityScenario<ToolConfirmationActivity> scenario=open(p.proposal.id)){
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"tool_details").textContains("Alarm request")),12000));
            click("tool_approve");status("TEST_PERFORMED");assertEquals(1,app.toolCalls());
            assertFalse(device.findObject(By.res(pkg,"tool_approve")).isEnabled());
            scenario.recreate();status("TEST_PERFORMED");assertEquals(1,app.toolCalls());
        }
    }
    @Test public void rejectionIsVisibleAndPerformsNoAction(){
        ToolCoordinator.Outcome p=proposal();
        try(ActivityScenario<ToolConfirmationActivity> scenario=open(p.proposal.id)){
            click("tool_reject");status("DECLINED");assertEquals(0,app.toolCalls());
        }
    }
    @Test public void expiredProposalCannotBeApproved(){
        ToolCoordinator.Outcome p=proposal();app.advanceToolTime(60001);
        try(ActivityScenario<ToolConfirmationActivity> scenario=open(p.proposal.id)){
            status("EXPIRED");assertFalse(device.findObject(By.res(pkg,"tool_approve")).isEnabled());assertEquals(0,app.toolCalls());
        }
    }
    @Test public void failedHandlerIsShownAsFailureAfterConsent(){
        app.failTool(true);ToolCoordinator.Outcome p=proposal();
        try(ActivityScenario<ToolConfirmationActivity> scenario=open(p.proposal.id)){
            click("tool_approve");status("TEST_FAILED");assertEquals(1,app.toolCalls());
            assertFalse(app.toolCoordinator().lookup(p.proposal.id).result.succeeded);
        }
    }
}
