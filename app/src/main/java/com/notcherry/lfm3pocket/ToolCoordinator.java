package com.notcherry.lfm3pocket;

import java.util.*;
import java.util.function.*;

/** One-shot authorization for immutable validated requests. Origin is supplied by app code, never JSON. */
public final class ToolCoordinator {
    public enum Source { USER_ACTION,MODEL_PROPOSAL,IMAGE,WEB_SEARCH,DOCUMENT,BACKGROUND,UNKNOWN }
    public enum Risk { SAFE,CONFIRM }
    public interface Handler { AndroidActionExecutor.ActionResult execute(ToolRegistry.Request request); }
    public interface PermissionPolicy {
        boolean allowed(ToolRegistry.Request request);
        String denialMessage(ToolRegistry.Request request);

        static PermissionPolicy fromPredicate(Predicate<ToolRegistry.Request> permission) {
            return new PermissionPolicy() {
                @Override public boolean allowed(ToolRegistry.Request request) { return permission.test(request); }
                @Override public String denialMessage(ToolRegistry.Request request) { return null; }
            };
        }
    }
    public static final class Proposal {
        public final String id;public final ToolRegistry.Request request;public final Risk risk;public final Source source;
        private final long expires;
        private Proposal(ToolRegistry.Request r,Source source,long now){id=UUID.randomUUID().toString();request=r;risk=risk(r);this.source=source;expires=now+60000;}
    }
    public static final class Outcome {
        public final String status;public final Proposal proposal;public final AndroidActionExecutor.ActionResult result;
        private Outcome(String status,Proposal proposal,AndroidActionExecutor.ActionResult result){this.status=status;this.proposal=proposal;this.result=result;}
    }
    private final Handler handler;private final PermissionPolicy permission;private final LongSupplier clock;
    private final Map<String,Proposal> pending=new LinkedHashMap<>();
    private final Map<String,Outcome> completed=new LinkedHashMap<>();
    public ToolCoordinator(Handler handler,Predicate<ToolRegistry.Request> permission,LongSupplier clock){
        this(handler, PermissionPolicy.fromPredicate(permission), clock);
    }
    public ToolCoordinator(Handler handler,PermissionPolicy permission,LongSupplier clock){this.handler=handler;this.permission=permission;this.clock=clock;}
    public static Risk risk(ToolRegistry.Request request){
        if(request==null)return Risk.CONFIRM;
        switch(request.tool){
            case FLASHLIGHT:case TIMER:case SHOW_ALARMS:case APPLICATION:case SETTINGS:
            case CURRENT_TIME:case CALCULATOR:case UNIT_CONVERT:case WEATHER:return Risk.SAFE;
            case VOLUME:return request instanceof ToolRegistry.VolumeRequest && ((ToolRegistry.VolumeRequest)request).stream.equals("music")?Risk.SAFE:Risk.CONFIRM;
            case LIST_NOTIFICATIONS:return Risk.CONFIRM;
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
    private boolean permitted(ToolRegistry.Request request){try{return permission.allowed(request);}catch(RuntimeException e){return false;}}
    private Outcome execute(ToolRegistry.Request request){
        AndroidActionExecutor.ActionResult result;
        try{result=handler.execute(request);if(result==null)throw new IllegalStateException("Missing execution result");}
        catch(Exception e){result=new AndroidActionExecutor.ActionResult(false,"FAILED",e.getMessage()==null?"Action failed":e.getMessage());}
        return new Outcome(result.status,null,result);
    }
    private static Outcome denied(String code,String message){return new Outcome(code,null,new AndroidActionExecutor.ActionResult(false,code,message));}
    private Outcome permissionDenied(ToolRegistry.Request request) {
        String message = null;
        try { message = permission.denialMessage(request); } catch (RuntimeException ignored) {}
        if (message == null || message.trim().isEmpty()) {
            message = "Required permission or policy is unavailable.";
            if (request != null && request.tool == ToolRegistry.Tool.FLASHLIGHT) {
                message = "Grant Camera permission in Android app settings to control the flashlight.";
            } else if (request != null && request.tool == ToolRegistry.Tool.WEATHER) {
                message = "Enable network tools in Settings before fetching weather.";
            } else if (request != null && request.tool == ToolRegistry.Tool.LIST_NOTIFICATIONS) {
                message = "Enable notification access in Settings, choose allowed apps, and grant system notification access.";
            } else if (request != null && (request.tool == ToolRegistry.Tool.LIST_EVENTS
                    || request.tool == ToolRegistry.Tool.CREATE_EVENT
                    || request.tool == ToolRegistry.Tool.UPDATE_EVENT
                    || request.tool == ToolRegistry.Tool.DELETE_EVENT)) {
                message = "Calendar permission or a picked calendar is unavailable.";
            }
        }
        return denied("PERMISSION_DENIED",message);
    }
    private void remember(String id,Outcome outcome){completed.put(id,outcome);while(completed.size()>64)completed.remove(completed.keySet().iterator().next());}
    private void expire(){for(Proposal p:new ArrayList<>(pending.values()))if(clock.getAsLong()>=p.expires){pending.remove(p.id);remember(p.id,denied("EXPIRED","Action approval expired"));}}
    public static String title(ToolRegistry.Request request){
        switch(request.tool){
            case FLASHLIGHT:return "Flashlight";case VOLUME:return "Audio volume";case TIMER:return "Timer request";
            case ALARM:return "Alarm request";case SHOW_ALARMS:return "Show alarms";
            case APPLICATION:return "Open app";case SETTINGS:return "Open settings";
            case COPY:return "Copy text";case SHARE:return "Share text";
            case REMINDER:return "Reminder";
            case CURRENT_TIME:return "Current time";case CALCULATOR:return "Calculator";
            case UNIT_CONVERT:return "Unit conversion";case WEATHER:return "Weather";
            case LIST_NOTIFICATIONS:return "List notifications";
            case DRAFT_MESSAGE:return "Draft message";case SEND_MESSAGE:return "Send message (open composer)";
            case LIST_EVENTS:return "List calendar events";
            case CREATE_EVENT:return "Create calendar event";
            case UPDATE_EVENT:return "Update calendar event";
            case DELETE_EVENT:return "Delete calendar event";
            default:return "Android action";
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
        appendCalendarReadback(out, request);
        return out.toString();
    }

    private static void appendCalendarReadback(StringBuilder out, ToolRegistry.Request request) {
        if (request == null) return;
        try {
            if (request instanceof ToolRegistry.ListEventsRequest) {
                ToolRegistry.ListEventsRequest r = (ToolRegistry.ListEventsRequest) request;
                out.append('\n').append("window (local): ").append(formatLocalTime(r.windowStartEpochSec))
                        .append(" - ").append(formatLocalTime(r.windowEndEpochSec));
            } else if (request instanceof ToolRegistry.CreateEventRequest) {
                ToolRegistry.CreateEventRequest r = (ToolRegistry.CreateEventRequest) request;
                out.append('\n').append("title (local read-back): ").append(r.title);
                out.append('\n').append("time (local): ").append(formatLocalTime(r.startEpochSec))
                        .append(" - ").append(formatLocalTime(r.endEpochSec));
            } else if (request instanceof ToolRegistry.UpdateEventRequest) {
                ToolRegistry.UpdateEventRequest r = (ToolRegistry.UpdateEventRequest) request;
                out.append('\n').append("title (local read-back): ").append(r.title);
                out.append('\n').append("time (local): ").append(formatLocalTime(r.startEpochSec))
                        .append(" - ").append(formatLocalTime(r.endEpochSec));
            }
        } catch (RuntimeException ignored) {
            // Read-back must not fail proposal display.
        }
    }

    private static String formatLocalTime(long epochSec) {
        try {
            java.time.ZonedDateTime time = java.time.Instant.ofEpochSecond(epochSec)
                    .atZone(java.time.ZoneId.systemDefault());
            return time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT));
        } catch (RuntimeException e) {
            return String.valueOf(epochSec);
        }
    }
}
