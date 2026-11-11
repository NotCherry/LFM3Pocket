package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ToolCoordinatorTest {
    private ToolRegistry.Request request(String tool,String args){return new ToolRegistry().parse("{\"tool\":\""+tool+"\",\"arguments\":"+args+"}").request;}
    @Test public void modelProposalsNeedConsentAndApprovalExecutesExactlyOnce(){
        AtomicInteger effects=new AtomicInteger();AtomicLong time=new AtomicLong();
        ToolCoordinator c=new ToolCoordinator(r->{effects.incrementAndGet();return new AndroidActionExecutor.ActionResult(true,"DONE","Test executed");},r->true,time::get);
        ToolCoordinator.Outcome pending=c.propose(request("toggle_flashlight","{\"on\":true}"),ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION",pending.status);assertEquals(0,effects.get());
        assertTrue(c.approve(pending.proposal.id).result.succeeded);assertEquals(1,effects.get());
        assertEquals("ALREADY_RESOLVED",c.approve(pending.proposal.id).status);assertEquals(1,effects.get());
    }
    @Test public void denialTimeoutRevocationAndUntrustedSourcesHaveNoSideEffects(){
        AtomicInteger effects=new AtomicInteger();AtomicLong time=new AtomicLong();AtomicBoolean permission=new AtomicBoolean(true);
        ToolCoordinator c=new ToolCoordinator(r->{effects.incrementAndGet();return new AndroidActionExecutor.ActionResult(true,"DONE","");},r->permission.get(),time::get);
        ToolRegistry.Request alarm=request("set_alarm","{\"hour\":7,\"minute\":30}");
        ToolCoordinator.Outcome p=c.propose(alarm,ToolCoordinator.Source.USER_ACTION);c.reject(p.proposal.id);assertEquals(0,effects.get());
        p=c.propose(alarm,ToolCoordinator.Source.MODEL_PROPOSAL);time.addAndGet(60001);assertEquals("EXPIRED",c.approve(p.proposal.id).status);
        p=c.propose(alarm,ToolCoordinator.Source.MODEL_PROPOSAL);permission.set(false);assertEquals("PERMISSION_DENIED",c.approve(p.proposal.id).status);
        permission.set(true);
        for(ToolCoordinator.Source source:new ToolCoordinator.Source[]{ToolCoordinator.Source.IMAGE,ToolCoordinator.Source.WEB_SEARCH,ToolCoordinator.Source.DOCUMENT,ToolCoordinator.Source.BACKGROUND,ToolCoordinator.Source.UNKNOWN})
            assertEquals("UNTRUSTED_SOURCE",c.propose(alarm,source).status);
        assertEquals(0,effects.get());
    }
    @Test public void riskMatrixDefaultsToConfirmationAndExplicitSafeUserActionCanRun(){
        assertEquals(ToolCoordinator.Risk.CONFIRM,ToolCoordinator.risk(null));
        assertEquals(ToolCoordinator.Risk.SAFE,ToolCoordinator.risk(request("toggle_flashlight","{\"on\":true}")));
        assertEquals(ToolCoordinator.Risk.SAFE,ToolCoordinator.risk(request("set_timer","{\"seconds\":60}")));
        assertEquals(ToolCoordinator.Risk.SAFE,ToolCoordinator.risk(request("open_application","{\"package\":\"com.android.settings\"}")));
        assertEquals(ToolCoordinator.Risk.SAFE,ToolCoordinator.risk(request("open_settings","{\"page\":\"wifi\"}")));
        assertEquals(ToolCoordinator.Risk.SAFE,ToolCoordinator.risk(request("set_volume","{\"stream\":\"music\",\"percent\":50}")));
        assertEquals(ToolCoordinator.Risk.CONFIRM,ToolCoordinator.risk(request("copy_text","{\"text\":\"Hi\"}")));
        assertEquals(ToolCoordinator.Risk.CONFIRM,ToolCoordinator.risk(request("set_alarm","{\"hour\":7,\"minute\":0}")));
        assertEquals(ToolCoordinator.Risk.CONFIRM,ToolCoordinator.risk(request("set_volume","{\"stream\":\"ring\",\"percent\":50}")));
        assertEquals(ToolCoordinator.Risk.CONFIRM,ToolCoordinator.risk(request("share_text","{\"text\":\"Hi\"}")));
        AtomicInteger effects=new AtomicInteger();
        ToolCoordinator c=new ToolCoordinator(r->{effects.incrementAndGet();return new AndroidActionExecutor.ActionResult(true,"DONE","");},r->true,()->0);
        assertTrue(c.propose(request("toggle_flashlight","{\"on\":false}"),ToolCoordinator.Source.USER_ACTION).result.succeeded);
        assertEquals(1,effects.get());
    }
    @Test public void handlerFailureNeverBecomesSuccessAndDisplayEscapesControlCharacters(){
        ToolCoordinator c=new ToolCoordinator(r->{throw new SecurityException("Denied");},r->true,()->0);
        ToolCoordinator.Outcome p=c.propose(request("copy_text","{\"text\":\"a\\n\\u202Ehidden\"}"),ToolCoordinator.Source.MODEL_PROPOSAL);
        assertTrue(ToolCoordinator.describe(p.proposal.request).contains("\\u202E"));
        assertFalse(c.approve(p.proposal.id).result.succeeded);
    }
}
