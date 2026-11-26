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
        public final boolean tainted;
        private final long expires;
        private Proposal(ToolRegistry.Request r,Source source,long now,boolean tainted){
            id=UUID.randomUUID().toString();request=r;risk=tainted?Risk.CONFIRM:risk(r);
            this.source=source;this.tainted=tainted;expires=now+60000;
        }
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
            case CURRENT_TIME:case CALCULATOR:case UNIT_CONVERT:case WEATHER:
            case WEB_SEARCH:case SEARCH_TOOLS:case FS_LIST:case FS_SEARCH:case FS_READ:
            case LOOKUP_CONTACT:return Risk.SAFE;
            case VOLUME:return request instanceof ToolRegistry.VolumeRequest && ((ToolRegistry.VolumeRequest)request).stream.equals("music")?Risk.SAFE:Risk.CONFIRM;
            case LIST_NOTIFICATIONS:return Risk.CONFIRM;
            default:return Risk.CONFIRM;
        }
    }
    public synchronized Outcome propose(ToolRegistry.Request request,Source source){
        return proposeInternal(request, source, false);
    }

    /**
     * Taint-aware proposal (ADR 0025): when untrusted document/web/workspace content was in context,
     * the proposal always requires explicit user confirmation and never auto-executes.
     */
    public synchronized Outcome proposeTainted(ToolRegistry.Request request,Source source){
        return proposeInternal(request, source, true);
    }

    private Outcome proposeInternal(ToolRegistry.Request request,Source source,boolean tainted){
        expire();
        if(request==null)return denied("INVALID_REQUEST","No validated request");
        if(source!=Source.USER_ACTION && source!=Source.MODEL_PROPOSAL)return denied("UNTRUSTED_SOURCE","Untrusted content cannot authorize Android actions");
        if(!permitted(request))return permissionDenied(request);
        // USER_ACTION means an explicit app control with these exact arguments, not any ordinary chat message.
        if(!tainted && source==Source.USER_ACTION && risk(request)==Risk.SAFE)return execute(request);
        if(pending.size()>=32)return denied("TOO_MANY_REQUESTS","Resolve pending actions first");
        Proposal proposal=new Proposal(request,source,clock.getAsLong(),tainted);pending.put(proposal.id,proposal);
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
            } else if (request != null && request.tool == ToolRegistry.Tool.WEB_SEARCH) {
                message = "Configure an HTTPS SearXNG endpoint and enable Web search in Settings.";
            } else if (request != null && request.tool == ToolRegistry.Tool.LIST_NOTIFICATIONS) {
                message = "Enable notification access in Settings, choose allowed apps, and grant system notification access.";
            } else if (request != null && (request.tool == ToolRegistry.Tool.LIST_EVENTS
                    || request.tool == ToolRegistry.Tool.CREATE_EVENT
                    || request.tool == ToolRegistry.Tool.UPDATE_EVENT
                    || request.tool == ToolRegistry.Tool.DELETE_EVENT)) {
                message = "Calendar permission or a picked calendar is unavailable.";
            } else if (request != null && isWorkspaceTool(request.tool)) {
                message = "Pick a Local Workspace folder in Settings before accessing files or notes.";
            } else if (request != null && request.tool == ToolRegistry.Tool.LOOKUP_CONTACT) {
                message = "Grant Contacts permission in Settings → External Tools & Shortcuts.";
            }
        }
        return denied("PERMISSION_DENIED",message);
    }
    private static boolean isWorkspaceTool(ToolRegistry.Tool tool) {
        return tool == ToolRegistry.Tool.FS_LIST || tool == ToolRegistry.Tool.FS_SEARCH
                || tool == ToolRegistry.Tool.FS_READ || tool == ToolRegistry.Tool.NOTE_CREATE
                || tool == ToolRegistry.Tool.FS_APPEND || tool == ToolRegistry.Tool.FS_EDIT
                || tool == ToolRegistry.Tool.FS_DELETE;
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
            case WEB_SEARCH:return "Web search";case SEARCH_TOOLS:return "Search tools";
            case LIST_NOTIFICATIONS:return "List notifications";
            case DRAFT_MESSAGE:return "Draft message";case SEND_MESSAGE:return "Send message (open composer)";
            case LIST_EVENTS:return "List calendar events";
            case CREATE_EVENT:return "Create calendar event";
            case UPDATE_EVENT:return "Update calendar event";
            case DELETE_EVENT:return "Delete calendar event";
            case FS_LIST:return "List workspace files";
            case FS_SEARCH:return "Search workspace";
            case FS_READ:return "Read workspace file";
            case NOTE_CREATE:return "Create Markdown note";
            case FS_APPEND:return "Append to file";
            case FS_EDIT:return "Edit workspace file";
            case FS_DELETE:return "Delete workspace file";
            case LOOKUP_CONTACT:return "Look up contact";
            case EXTERNAL:
                if (request instanceof ToolRegistry.ExternalToolRequest) {
                    String wire = ((ToolRegistry.ExternalToolRequest) request).externalWireName;
                    return wire.startsWith("mcp.") ? "MCP tool" : "External shortcut";
                }
                return "External tool";
            default:return "Android action";
        }
    }
    public static String describe(ToolRegistry.Request request){
        StringBuilder out=new StringBuilder(title(request));
        for(Map.Entry<String,Object> entry:request.arguments.entrySet()){
            out.append('\n').append(entry.getKey()).append(": ");Object value=entry.getValue();
            if(!(value instanceof String)){out.append(value);continue;}
            out.append('"');appendEscaped(out,(String)value);out.append('"');
        }
        appendCalendarReadback(out, request);
        return out.toString();
    }

    /**
     * Human-readable card summary for UI display. Formats calendar, reminder, workspace, external, and web-search
     * requests cleanly (local times, diff previews, no raw epochs or empty optional fields), while
     * delegating other tools to {@link #describe(ToolRegistry.Request)}.
     */
    public static String formatForDisplay(ToolRegistry.Request request, String pickedCalendarLabel) {
        return formatForDisplay(request, pickedCalendarLabel, false);
    }

    public static String formatForDisplay(ToolRegistry.Request request, String pickedCalendarLabel, boolean tainted) {
        String body = formatBodyForDisplay(request, pickedCalendarLabel);
        if (!tainted) return body;
        return body + "\n\nNote: Proposed while reading attached/external content. Verify details before approving.";
    }

    private static String formatBodyForDisplay(ToolRegistry.Request request, String pickedCalendarLabel) {
        if (request == null) return "Android action";
        if (request instanceof ToolRegistry.ExternalToolRequest) {
            ToolRegistry.ExternalToolRequest ext = (ToolRegistry.ExternalToolRequest) request;
            if (ext.displaySummary != null && !ext.displaySummary.trim().isEmpty()) {
                return ext.displaySummary.trim();
            }
            return title(request) + "\nTool: " + ext.externalWireName;
        }
        if (request instanceof ToolRegistry.LookupContactRequest) {
            ToolRegistry.LookupContactRequest r = (ToolRegistry.LookupContactRequest) request;
            return title(request) + "\nQuery: " + r.query + "\nLimit: " + r.limit;
        }
        if (request instanceof ToolRegistry.CreateEventRequest) {
            ToolRegistry.CreateEventRequest r = (ToolRegistry.CreateEventRequest) request;
            StringBuilder out = new StringBuilder(title(request));
            out.append("\nTitle: "); appendEscaped(out, r.title);
            if (r.allDay) {
                out.append("\nDate: ").append(formatLocalDateRange(r.startEpochSec, r.endEpochSec)).append(" (All day)");
            } else {
                out.append("\nWhen: ").append(formatLocalTime(r.startEpochSec))
                        .append(" – ").append(formatLocalTime(r.endEpochSec));
            }
            if (pickedCalendarLabel != null && !pickedCalendarLabel.trim().isEmpty()) {
                out.append("\nCalendar: "); appendEscaped(out, pickedCalendarLabel.trim());
            }
            if (r.location != null && !r.location.trim().isEmpty()) {
                out.append("\nLocation: "); appendEscaped(out, r.location.trim());
            }
            if (r.notes != null && !r.notes.trim().isEmpty()) {
                out.append("\nNotes: "); appendEscaped(out, r.notes.trim());
            }
            return out.toString();
        }
        if (request instanceof ToolRegistry.UpdateEventRequest) {
            ToolRegistry.UpdateEventRequest r = (ToolRegistry.UpdateEventRequest) request;
            StringBuilder out = new StringBuilder(title(request));
            out.append("\nTitle: "); appendEscaped(out, r.title);
            out.append("\nWhen: ").append(formatLocalTime(r.startEpochSec))
                    .append(" – ").append(formatLocalTime(r.endEpochSec));
            out.append("\nEvent ID: "); appendEscaped(out, r.eventId);
            return out.toString();
        }
        if (request instanceof ToolRegistry.ListEventsRequest) {
            ToolRegistry.ListEventsRequest r = (ToolRegistry.ListEventsRequest) request;
            return title(request)
                    + "\nWindow: " + formatLocalTime(r.windowStartEpochSec)
                    + " – " + formatLocalTime(r.windowEndEpochSec)
                    + "\nLimit: " + r.limit;
        }
        if (request instanceof ToolRegistry.DeleteEventRequest) {
            ToolRegistry.DeleteEventRequest r = (ToolRegistry.DeleteEventRequest) request;
            StringBuilder out = new StringBuilder(title(request));
            out.append("\nEvent ID: "); appendEscaped(out, r.eventId);
            return out.toString();
        }
        if (request instanceof ToolRegistry.ReminderRequest) {
            ToolRegistry.ReminderRequest r = (ToolRegistry.ReminderRequest) request;
            StringBuilder out = new StringBuilder(title(request));
            if (r.title != null && !r.title.isEmpty()) {
                out.append("\nTitle: "); appendEscaped(out, r.title);
            }
            out.append("\nWhen: ").append(formatLocalTime(r.whenEpochSec));
            if (r.body != null && !r.body.isEmpty()) {
                out.append("\nDetails: "); appendEscaped(out, r.body);
            }
            return out.toString();
        }
        if (request instanceof ToolRegistry.WebSearchRequest) {
            ToolRegistry.WebSearchRequest r = (ToolRegistry.WebSearchRequest) request;
            StringBuilder out = new StringBuilder(title(request));
            out.append("\nQuery: "); appendEscaped(out, r.query);
            if (r.timeRange != null && !r.timeRange.isEmpty()) {
                out.append("\nRange: ").append(r.timeRange);
            }
            return out.toString();
        }
        if (request instanceof ToolRegistry.NoteCreateRequest) {
            ToolRegistry.NoteCreateRequest r = (ToolRegistry.NoteCreateRequest) request;
            StringBuilder out = new StringBuilder(title(request));
            out.append("\nTitle: "); appendEscaped(out, r.title);
            if (r.folder != null && !r.folder.isEmpty()) {
                out.append("\nFolder: "); appendEscaped(out, r.folder);
            }
            if (r.tags != null && !r.tags.isEmpty()) {
                out.append("\nTags: "); appendEscaped(out, r.tags);
            }
            out.append("\nPreview:\n").append(truncatePreview(r.content, 240));
            return out.toString();
        }
        if (request instanceof ToolRegistry.FsAppendRequest) {
            ToolRegistry.FsAppendRequest r = (ToolRegistry.FsAppendRequest) request;
            return title(request) + "\nFile: " + r.path + "\nAppend:\n" + truncatePreview(r.content, 240);
        }
        if (request instanceof ToolRegistry.FsEditRequest) {
            ToolRegistry.FsEditRequest r = (ToolRegistry.FsEditRequest) request;
            return title(request) + "\nFile: " + r.path
                    + "\n- " + truncatePreview(r.oldText, 140)
                    + "\n+ " + truncatePreview(r.newText, 140);
        }
        if (request instanceof ToolRegistry.FsDeleteRequest) {
            ToolRegistry.FsDeleteRequest r = (ToolRegistry.FsDeleteRequest) request;
            return title(request) + "\nFile: " + r.path;
        }
        return describe(request);
    }

    private static String truncatePreview(String text, int max) {
        if (text == null) return "";
        String clean = text.replace("\r\n", "\n").trim();
        if (clean.length() <= max) return clean;
        return clean.substring(0, max) + "…";
    }

    /** Strips internal prompt-boundary markers (e.g. {@code UNTRUSTED_CALENDAR}) for human UI display. */
    public static String stripUntrustedPrefix(String message) {
        if (message == null || message.isEmpty()) return "";
        String cleaned = message.replaceFirst("^UNTRUSTED_[A-Z_]+\\s*", "");
        return cleaned.trim();
    }

    /** Returns the effective wire name for a validated request (including dynamic external/MCP wire names). */
    public static String wireName(ToolRegistry.Request request) {
        if (request == null) return "tool";
        if (request instanceof ToolRegistry.ExternalToolRequest) {
            return ((ToolRegistry.ExternalToolRequest) request).externalWireName;
        }
        return request.tool.wireName;
    }

    /** Human-readable title from a stored wire name (for History & restored live chat tool rows). */
    public static String titleForWireName(String wireName) {
        if (wireName == null || wireName.trim().isEmpty()) return "Tool call";
        String w = wireName.trim();
        switch (w) {
            case "toggle_flashlight": return "Flashlight";
            case "set_volume": return "Audio volume";
            case "set_timer": return "Set timer";
            case "set_alarm": return "Set alarm";
            case "show_alarms": return "Show alarms";
            case "open_application": return "Open app";
            case "open_settings": return "Open settings";
            case "copy_text": return "Copy text";
            case "share_text": return "Share text";
            case "set_reminder": return "Reminder";
            case "current_time": return "Current time";
            case "calculator": return "Calculator";
            case "unit_convert": return "Unit conversion";
            case "weather": return "Weather";
            case "web_search": return "Web search";
            case "search_tools": return "Search tools";
            case "list_notifications": return "List notifications";
            case "draft_message": return "Draft message";
            case "send_message": return "Send message";
            case "list_events": return "List calendar events";
            case "create_event": return "Create calendar event";
            case "update_event": return "Update calendar event";
            case "delete_event": return "Delete calendar event";
            case "fs_list": return "List workspace files";
            case "fs_search": return "Search workspace";
            case "fs_read": return "Read workspace file";
            case "note_create": return "Create Markdown note";
            case "fs_append": return "Append to file";
            case "fs_edit": return "Edit workspace file";
            case "fs_delete": return "Delete workspace file";
            case "lookup_contact": return "Look up contact";
            default:
                if (w.startsWith("mcp.")) {
                    int dot = w.lastIndexOf('.');
                    return "MCP · " + (dot >= 0 && dot + 1 < w.length() ? w.substring(dot + 1) : w);
                }
                if (w.startsWith("shortcut.")) {
                    int dot = w.lastIndexOf('.');
                    return "Shortcut · " + (dot >= 0 && dot + 1 < w.length() ? w.substring(dot + 1) : w);
                }
                return w;
        }
    }

    /** Short one-line argument target for collapsed Antigravity-style tool rows. */
    public static String compactSummary(ToolRegistry.Request request) {
        if (request == null) return "";
        if (request instanceof ToolRegistry.FsListRequest) {
            String p = ((ToolRegistry.FsListRequest) request).path;
            return (p == null || p.isEmpty()) ? "/" : p;
        }
        if (request instanceof ToolRegistry.FsSearchRequest) {
            return truncateInline(((ToolRegistry.FsSearchRequest) request).query, 64);
        }
        if (request instanceof ToolRegistry.FsReadRequest) {
            return truncateInline(((ToolRegistry.FsReadRequest) request).path, 64);
        }
        if (request instanceof ToolRegistry.NoteCreateRequest) {
            return truncateInline(((ToolRegistry.NoteCreateRequest) request).title, 64);
        }
        if (request instanceof ToolRegistry.FsAppendRequest) {
            return truncateInline(((ToolRegistry.FsAppendRequest) request).path, 64);
        }
        if (request instanceof ToolRegistry.FsEditRequest) {
            return truncateInline(((ToolRegistry.FsEditRequest) request).path, 64);
        }
        if (request instanceof ToolRegistry.FsDeleteRequest) {
            return truncateInline(((ToolRegistry.FsDeleteRequest) request).path, 64);
        }
        if (request instanceof ToolRegistry.SearchToolsRequest) {
            return truncateInline(((ToolRegistry.SearchToolsRequest) request).query, 64);
        }
        if (request instanceof ToolRegistry.WebSearchRequest) {
            return truncateInline(((ToolRegistry.WebSearchRequest) request).query, 64);
        }
        if (request instanceof ToolRegistry.CalculatorRequest) {
            return truncateInline(((ToolRegistry.CalculatorRequest) request).expression, 64);
        }
        if (request instanceof ToolRegistry.WeatherRequest) {
            ToolRegistry.WeatherRequest w = (ToolRegistry.WeatherRequest) request;
            return w.location != null && !w.location.isEmpty() ? truncateInline(w.location, 64) : "";
        }
        if (request instanceof ToolRegistry.TimerRequest) {
            ToolRegistry.TimerRequest t = (ToolRegistry.TimerRequest) request;
            return t.label != null && !t.label.isEmpty() ? t.seconds + "s · " + truncateInline(t.label, 40) : t.seconds + "s";
        }
        if (request instanceof ToolRegistry.CreateEventRequest) {
            return truncateInline(((ToolRegistry.CreateEventRequest) request).title, 64);
        }
        if (request instanceof ToolRegistry.LookupContactRequest) {
            return truncateInline(((ToolRegistry.LookupContactRequest) request).query, 64);
        }
        if (request instanceof ToolRegistry.CurrentTimeRequest) {
            String tz = ((ToolRegistry.CurrentTimeRequest) request).timezone;
            return tz == null || tz.isEmpty() ? "local" : truncateInline(tz, 48);
        }
        return firstArgSummary(request.arguments);
    }

    /** Short one-line argument target parsed from stored JSON arguments (for History / restored turns). */
    @SuppressWarnings("unchecked")
    public static String compactSummaryFromJson(String wireName, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
            return "fs_list".equals(wireName) ? "/" : "";
        }
        try {
            Object parsed = StrictToolJson.parseBounded(argumentsJson, 32_768, 6, 64);
            if (!(parsed instanceof Map)) return "";
            Map<String, Object> args = (Map<String, Object>) parsed;
            if ("fs_list".equals(wireName)) {
                Object p = args.get("path");
                String s = p == null ? "" : String.valueOf(p).trim();
                return s.isEmpty() ? "/" : truncateInline(s, 64);
            }
            if ("set_timer".equals(wireName) && args.containsKey("seconds")) {
                Object sec = args.get("seconds");
                Object lbl = args.get("label");
                String label = lbl == null ? "" : String.valueOf(lbl).trim();
                return label.isEmpty() ? sec + "s" : sec + "s · " + truncateInline(label, 40);
            }
            for (String preferred : new String[] { "path", "query", "title", "expression", "location", "package", "text" }) {
                if (args.containsKey(preferred)) {
                    String v = String.valueOf(args.get(preferred)).trim();
                    if (!v.isEmpty()) return truncateInline(v, 64);
                }
            }
            return firstArgSummary(args);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String firstArgSummary(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (e.getValue() == null) continue;
            String v = String.valueOf(e.getValue()).trim();
            if (!v.isEmpty()) return truncateInline(v, 64);
        }
        return "";
    }

    private static String truncateInline(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max - 1) + "…";
    }

    private static void appendEscaped(StringBuilder out, String text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == '\u2028' || c == '\u2029')
                out.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
            else {
                if (c == '"' || c == '\\') out.append('\\');
                out.append(c);
            }
        }
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

    private static String formatLocalDateRange(long startEpochSec, long endEpochSec) {
        try {
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            java.time.LocalDate start = java.time.Instant.ofEpochSecond(startEpochSec).atZone(zone).toLocalDate();
            java.time.LocalDate end = java.time.Instant.ofEpochSecond(Math.max(startEpochSec, endEpochSec)).atZone(zone).toLocalDate();
            if (!end.isAfter(start.plusDays(1))) return start.toString();
            return start + " – " + end.minusDays(1);
        } catch (RuntimeException e) {
            return formatLocalTime(startEpochSec);
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
