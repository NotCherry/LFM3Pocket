package com.notcherry.lfm3pocket;

import java.util.*;
import java.util.function.*;

/** One-shot authorization for immutable validated requests. Origin is supplied by app code, never JSON. */
public final class ToolCoordinator {
    public enum Source { USER_ACTION,MODEL_PROPOSAL,IMAGE,WEB_SEARCH,DOCUMENT,BACKGROUND,UNKNOWN }
    public enum Risk { SAFE,CONFIRM }
    public interface Handler { AndroidActionExecutor.ActionResult execute(ToolRegistry.Request request); }
    public static final class Proposal {
        public final String id;public final ToolRegistry.Request request;public final Risk risk;public final Source source;
        private final long expires;
        private Proposal(ToolRegistry.Request r,Source source,long now){id=UUID.randomUUID().toString();request=r;risk=risk(r);this.source=source;expires=now+60000;}
    }
    public static final class Outcome {
        public final String status;public final Proposal proposal;public final AndroidActionExecutor.ActionResult result;
        private Outcome(String status,Proposal proposal,AndroidActionExecutor.ActionResult result){this.status=status;this.proposal=proposal;this.result=result;}
    }
    private final Handler handler;private final Predicate<ToolRegistry.Request> permission;private final LongSupplier clock;
    private final Map<String,Proposal> pending=new LinkedHashMap<>();
    private final Map<String,Outcome> completed=new LinkedHashMap<>();
    public ToolCoordinator(Handler handler,Predicate<ToolRegistry.Request> permission,LongSupplier clock){this.handler=handler;this.permission=permission;this.clock=clock;}
    public static Risk risk(ToolRegistry.Request request){
        if(request==null)return Risk.CONFIRM;
        switch(request.tool){
            case FLASHLIGHT:case TIMER:case APPLICATION:case SETTINGS:return Risk.SAFE;
            case VOLUME:return request instanceof ToolRegistry.VolumeRequest && ((ToolRegistry.VolumeRequest)request).stream.equals("music")?Risk.SAFE:Risk.CONFIRM;
            default:return Risk.CONFIRM;
        }
    }
    public synchronized Outcome propose(ToolRegistry.Request request,Source source){
        expire();
        if(request==null)return denied("INVALID_REQUEST","No validated request");
        if(source!=Source.USER_ACTION && source!=Source.MODEL_PROPOSAL)return denied("UNTRUSTED_SOURCE","Untrusted content cannot authorize Android actions");
        if(!permitted(request))return permissionDenied(request);
        // USER_ACTION means an explicit app control with these exact arguments, not any ordinary chat message.
        if(source==Source.USER_ACTION && risk(request)==Risk.SAFE)return execute(request);
        if(pending.size()>=32)return denied("TOO_MANY_REQUESTS","Resolve pending actions first");
        Proposal proposal=new Proposal(request,source,clock.getAsLong());pending.put(proposal.id,proposal);
        return new Outcome("PENDING_CONFIRMATION",proposal,null);
    }
    public synchronized Outcome lookup(String id){
        expire();Proposal p=pending.get(id);
        return p!=null?new Outcome("PENDING_CONFIRMATION",p,null):completed.containsKey(id)?completed.get(id):denied("UNKNOWN_PROPOSAL","Action is no longer pending");
    }
    public synchronized Outcome approve(String id){
        Proposal p=pending.remove(id);
        if(p==null)return denied(completed.containsKey(id)?"ALREADY_RESOLVED":"UNKNOWN_PROPOSAL","Approval cannot be reused");
        Outcome result=clock.getAsLong()>=p.expires?denied("EXPIRED","Action approval expired")
                :!permitted(p.request)?permissionDenied(p.request):execute(p.request);
        remember(id,result);return result;
    }
    public synchronized Outcome reject(String id){
        Proposal p=pending.remove(id);
        if(p==null)return denied(completed.containsKey(id)?"ALREADY_RESOLVED":"UNKNOWN_PROPOSAL","Action is no longer pending");
        Outcome result=denied("DECLINED","Action was not executed");remember(id,result);return result;
    }
    private boolean permitted(ToolRegistry.Request request){try{return permission.test(request);}catch(RuntimeException e){return false;}}
    private Outcome execute(ToolRegistry.Request request){
        AndroidActionExecutor.ActionResult result;
        try{result=handler.execute(request);if(result==null)throw new IllegalStateException("Missing execution result");}
        catch(Exception e){result=new AndroidActionExecutor.ActionResult(false,"FAILED",e.getMessage()==null?"Action failed":e.getMessage());}
        return new Outcome(result.status,null,result);
    }
    private static Outcome denied(String code,String message){return new Outcome(code,null,new AndroidActionExecutor.ActionResult(false,code,message));}
    private static Outcome permissionDenied(ToolRegistry.Request request) {
        String message = request != null && request.tool == ToolRegistry.Tool.FLASHLIGHT
                ? "Grant Camera permission in Android app settings to control the flashlight."
                : "Required permission or policy is unavailable.";
        return denied("PERMISSION_DENIED",message);
    }
    private void remember(String id,Outcome outcome){completed.put(id,outcome);while(completed.size()>64)completed.remove(completed.keySet().iterator().next());}
    private void expire(){for(Proposal p:new ArrayList<>(pending.values()))if(clock.getAsLong()>=p.expires){pending.remove(p.id);remember(p.id,denied("EXPIRED","Action approval expired"));}}
    public static String title(ToolRegistry.Request request){
        switch(request.tool){
            case FLASHLIGHT:return "Flashlight";case VOLUME:return "Audio volume";case TIMER:return "Timer request";
            case ALARM:return "Alarm request";case APPLICATION:return "Open app";case SETTINGS:return "Open settings";
            case COPY:return "Copy text";case SHARE:return "Share text";default:return "Android action";
        }
    }
    public static String describe(ToolRegistry.Request request){
        StringBuilder out=new StringBuilder(title(request));
        for(Map.Entry<String,Object> entry:request.arguments.entrySet()){
            out.append('\n').append(entry.getKey()).append(": ");Object value=entry.getValue();
            if(!(value instanceof String)){out.append(value);continue;}
            out.append('"');String text=(String)value;
            for(int i=0;i<text.length();i++){
                char c=text.charAt(i);
                if(Character.isISOControl(c) || Character.getType(c)==Character.FORMAT || c=='\u2028' || c=='\u2029')
                    out.append(String.format(Locale.ROOT,"\\u%04X",(int)c));
                else {if(c=='"' || c=='\\')out.append('\\');out.append(c);}
            }
            out.append('"');
        }
        return out.toString();
    }
}
