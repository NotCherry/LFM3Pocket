package com.notcherry.lfm3pocket;

import java.util.*;

/** Validation produces data only. Authorization and Android execution are separate mandatory steps. */
public final class ToolRegistry {
    public enum Tool {
        FLASHLIGHT("toggle_flashlight"),VOLUME("set_volume"),TIMER("set_timer"),ALARM("set_alarm"),
        REMINDER("set_reminder"),SHOW_ALARMS("show_alarms"),APPLICATION("open_application"),SETTINGS("open_settings"),
        COPY("copy_text"),SHARE("share_text"),CURRENT_TIME("current_time"),CALCULATOR("calculator"),
        UNIT_CONVERT("unit_convert"),WEATHER("weather"),LIST_NOTIFICATIONS("list_notifications"),
        DRAFT_MESSAGE("draft_message"),SEND_MESSAGE("send_message"),
        LIST_EVENTS("list_events"),CREATE_EVENT("create_event"),UPDATE_EVENT("update_event"),DELETE_EVENT("delete_event"),
        WEB_SEARCH("web_search"),SEARCH_TOOLS("search_tools"),
        FS_LIST("fs_list"),FS_SEARCH("fs_search"),FS_READ("fs_read"),
        NOTE_CREATE("note_create"),FS_APPEND("fs_append"),FS_EDIT("fs_edit"),FS_DELETE("fs_delete");
        public final String wireName;Tool(String name){wireName=name;}
    }
    public static abstract class Request {
        public final Tool tool;public final Map<String,Object> arguments;
        private Request(Tool tool,Map<String,Object> arguments){this.tool=tool;this.arguments=Collections.unmodifiableMap(new LinkedHashMap<>(arguments));}
    }
    public static final class FlashlightRequest extends Request {
        public final boolean on;private FlashlightRequest(Map<String,Object> a){super(Tool.FLASHLIGHT,a);on=(Boolean)a.get("on");}
    }
    public static final class VolumeRequest extends Request {
        public final String stream;public final int percent;
        private VolumeRequest(Map<String,Object> a){super(Tool.VOLUME,a);stream=(String)a.get("stream");percent=(Integer)a.get("percent");}
    }
    public static final class TimerRequest extends Request {
        public final int seconds;public final String label;
        private TimerRequest(Map<String,Object> a){super(Tool.TIMER,a);seconds=(Integer)a.get("seconds");label=(String)a.get("label");}
    }
    public static final class AlarmRequest extends Request {
        public final int hour,minute;public final String label;public final List<Integer> days;
        @SuppressWarnings("unchecked")
        private AlarmRequest(Map<String,Object> a){
            super(Tool.ALARM,a);hour=(Integer)a.get("hour");minute=(Integer)a.get("minute");label=(String)a.get("label");
            Object raw=a.get("days");
            days=raw instanceof List ? Collections.unmodifiableList(new ArrayList<>((List<Integer>)raw)) : Collections.emptyList();
        }
    }
    public static final class ReminderRequest extends Request {
        public final long whenEpochSec;public final String title;public final String body;
        private ReminderRequest(Map<String,Object> a){
            super(Tool.REMINDER,a);
            Object w=a.get("when_epoch_sec");
            whenEpochSec=w instanceof Long ? (Long)w : ((Number)w).longValue();
            title=(String)a.get("title");body=(String)a.get("body");
        }
    }
    public static final class ShowAlarmsRequest extends Request {
        private ShowAlarmsRequest(Map<String,Object> a){super(Tool.SHOW_ALARMS,a);}
    }
    public static final class ApplicationRequest extends Request {
        public final String packageName;private ApplicationRequest(Map<String,Object> a){super(Tool.APPLICATION,a);packageName=(String)a.get("package");}
    }
    public static final class SettingsRequest extends Request {
        public final String page;private SettingsRequest(Map<String,Object> a){super(Tool.SETTINGS,a);page=(String)a.get("page");}
    }
    public static final class TextRequest extends Request {
        public final String text;private TextRequest(Tool tool,Map<String,Object> a){super(tool,a);text=(String)a.get("text");}
    }
    public static final class CurrentTimeRequest extends Request {
        public final String timezone;
        private CurrentTimeRequest(Map<String,Object> a){super(Tool.CURRENT_TIME,a);timezone=(String)a.get("timezone");}
    }
    public static final class CalculatorRequest extends Request {
        public final String expression;
        private CalculatorRequest(Map<String,Object> a){super(Tool.CALCULATOR,a);expression=(String)a.get("expression");}
    }
    public static final class UnitConvertRequest extends Request {
        public final double value;public final String fromUnit;public final String toUnit;
        private UnitConvertRequest(Map<String,Object> a){
            super(Tool.UNIT_CONVERT,a);value=((Number)a.get("value")).doubleValue();
            fromUnit=(String)a.get("from");toUnit=(String)a.get("to");
        }
    }
    public static final class WeatherRequest extends Request {
        public final String location;public final Double latitude;public final Double longitude;
        private WeatherRequest(Map<String,Object> a){
            super(Tool.WEATHER,a);
            location=(String)a.get("location");
            latitude=a.containsKey("latitude")?((Number)a.get("latitude")).doubleValue():null;
            longitude=a.containsKey("longitude")?((Number)a.get("longitude")).doubleValue():null;
        }
    }
    public static final class ListNotificationsRequest extends Request {
        public final int limit;public final String packageName;
        private ListNotificationsRequest(Map<String,Object> a){
            super(Tool.LIST_NOTIFICATIONS,a);
            limit=(Integer)a.get("limit");
            packageName=(String)a.get("package");
        }
    }
    public static final class DraftMessageRequest extends Request {
        public final String channel,to,body,packageName;
        private DraftMessageRequest(Map<String,Object> a){
            super(Tool.DRAFT_MESSAGE,a);
            channel=(String)a.get("channel");
            to=a.containsKey("to")?(String)a.get("to"):"";
            body=(String)a.get("body");
            packageName=a.containsKey("package")?(String)a.get("package"):null;
        }
    }
    public static final class SendMessageRequest extends Request {
        public final String draftId;
        private SendMessageRequest(Map<String,Object> a){super(Tool.SEND_MESSAGE,a);draftId=(String)a.get("draft_id");}
    }
    public static final class ListEventsRequest extends Request {
        public final long windowStartEpochSec,windowEndEpochSec;public final int limit;
        private ListEventsRequest(Map<String,Object> a){
            super(Tool.LIST_EVENTS,a);
            windowStartEpochSec=((Number)a.get("window_start_epoch_sec")).longValue();
            windowEndEpochSec=((Number)a.get("window_end_epoch_sec")).longValue();
            limit=((Number)a.get("limit")).intValue();
        }
    }
    public static final class CreateEventRequest extends Request {
        public final String title,calendarId,location,notes;
        public final long startEpochSec,endEpochSec;public final boolean allDay;
        private CreateEventRequest(Map<String,Object> a){
            super(Tool.CREATE_EVENT,a);
            title=(String)a.get("title");
            startEpochSec=((Number)a.get("start_epoch_sec")).longValue();
            endEpochSec=((Number)a.get("end_epoch_sec")).longValue();
            calendarId=(String)a.get("calendar_id");
            allDay=(Boolean)a.get("all_day");
            location=(String)a.get("location");
            notes=(String)a.get("notes");
        }
    }
    public static final class UpdateEventRequest extends Request {
        public final String eventId,title;public final long startEpochSec,endEpochSec;
        private UpdateEventRequest(Map<String,Object> a){
            super(Tool.UPDATE_EVENT,a);
            eventId=(String)a.get("event_id");
            title=(String)a.get("title");
            startEpochSec=((Number)a.get("start_epoch_sec")).longValue();
            endEpochSec=((Number)a.get("end_epoch_sec")).longValue();
        }
    }
    public static final class DeleteEventRequest extends Request {
        public final String eventId;
        private DeleteEventRequest(Map<String,Object> a){super(Tool.DELETE_EVENT,a);eventId=(String)a.get("event_id");}
    }
    public static final class WebSearchRequest extends Request {
        public final String query,timeRange;
        private WebSearchRequest(Map<String,Object> a){
            super(Tool.WEB_SEARCH,a);
            query=(String)a.get("query");
            timeRange=(String)a.get("time_range");
        }
    }
    public static final class SearchToolsRequest extends Request {
        public final String query;
        private SearchToolsRequest(Map<String,Object> a){
            super(Tool.SEARCH_TOOLS,a);
            query=(String)a.get("query");
        }
    }
    public static final class FsListRequest extends Request {
        public final String path;public final int maxDepth;
        private FsListRequest(Map<String,Object> a){
            super(Tool.FS_LIST,a);
            path=(String)a.get("path");
            maxDepth=((Number)a.get("max_depth")).intValue();
        }
    }
    public static final class FsSearchRequest extends Request {
        public final String query,path;
        private FsSearchRequest(Map<String,Object> a){
            super(Tool.FS_SEARCH,a);
            query=(String)a.get("query");
            path=(String)a.get("path");
        }
    }
    public static final class FsReadRequest extends Request {
        public final String path;public final int startLine,maxLines;
        private FsReadRequest(Map<String,Object> a){
            super(Tool.FS_READ,a);
            path=(String)a.get("path");
            startLine=((Number)a.get("start_line")).intValue();
            maxLines=((Number)a.get("max_lines")).intValue();
        }
    }
    public static final class NoteCreateRequest extends Request {
        public final String title,content,folder,tags;
        private NoteCreateRequest(Map<String,Object> a){
            super(Tool.NOTE_CREATE,a);
            title=(String)a.get("title");
            content=(String)a.get("content");
            folder=(String)a.get("folder");
            tags=(String)a.get("tags");
        }
    }
    public static final class FsAppendRequest extends Request {
        public final String path,content;
        private FsAppendRequest(Map<String,Object> a){
            super(Tool.FS_APPEND,a);
            path=(String)a.get("path");
            content=(String)a.get("content");
        }
    }
    public static final class FsEditRequest extends Request {
        public final String path,oldText,newText;
        private FsEditRequest(Map<String,Object> a){
            super(Tool.FS_EDIT,a);
            path=(String)a.get("path");
            oldText=(String)a.get("old_text");
            newText=(String)a.get("new_text");
        }
    }
    public static final class FsDeleteRequest extends Request {
        public final String path;
        private FsDeleteRequest(Map<String,Object> a){
            super(Tool.FS_DELETE,a);
            path=(String)a.get("path");
        }
    }
    private static Request request(Tool tool,Map<String,Object> args){
        switch(tool){
            case FLASHLIGHT:return new FlashlightRequest(args);case VOLUME:return new VolumeRequest(args);
            case TIMER:return new TimerRequest(args);case ALARM:return new AlarmRequest(args);
            case REMINDER:return new ReminderRequest(args);case SHOW_ALARMS:return new ShowAlarmsRequest(args);
            case APPLICATION:return new ApplicationRequest(args);case SETTINGS:return new SettingsRequest(args);
            case COPY:case SHARE:return new TextRequest(tool,args);
            case CURRENT_TIME:return new CurrentTimeRequest(args);
            case CALCULATOR:return new CalculatorRequest(args);
            case UNIT_CONVERT:return new UnitConvertRequest(args);
            case WEATHER:return new WeatherRequest(args);
            case LIST_NOTIFICATIONS:return new ListNotificationsRequest(args);
            case DRAFT_MESSAGE:return new DraftMessageRequest(args);
            case SEND_MESSAGE:return new SendMessageRequest(args);
            case LIST_EVENTS:return new ListEventsRequest(args);
            case CREATE_EVENT:return new CreateEventRequest(args);
            case UPDATE_EVENT:return new UpdateEventRequest(args);
            case DELETE_EVENT:return new DeleteEventRequest(args);
            case WEB_SEARCH:return new WebSearchRequest(args);
            case SEARCH_TOOLS:return new SearchToolsRequest(args);
            case FS_LIST:return new FsListRequest(args);
            case FS_SEARCH:return new FsSearchRequest(args);
            case FS_READ:return new FsReadRequest(args);
            case NOTE_CREATE:return new NoteCreateRequest(args);
            case FS_APPEND:return new FsAppendRequest(args);
            case FS_EDIT:return new FsEditRequest(args);
            case FS_DELETE:return new FsDeleteRequest(args);
            default:throw new IllegalArgumentException("Unregistered tool");
        }
    }
    private static final Set<String> ALLOWED_SHARE_PKGS = new HashSet<>(Arrays.asList("com.whatsapp","org.telegram.messenger","org.thoughtcrime.securesms"));
    private final java.util.function.LongSupplier nowEpochSec;
    public ToolRegistry(){this(()->System.currentTimeMillis()/1000L);}
    public ToolRegistry(java.util.function.LongSupplier nowEpochSec){
        if(nowEpochSec==null)throw new IllegalArgumentException("Missing clock");
        this.nowEpochSec=nowEpochSec;
    }
    public static final class Parsed {
        public final Request request;public final String status,message;
        private Parsed(Request request,String status,String message){this.request=request;this.status=status;this.message=message;}
        public boolean isValid(){return request!=null;}
    }
    private static Parsed reject(String status,String message){return new Parsed(null,status,message);}
    @SuppressWarnings("unchecked")
    public Parsed parse(String json){
        Object root;
        try{root=StrictToolJson.parse(json);}catch(IllegalArgumentException e){return reject("INVALID_JSON","Malformed or excessive tool JSON");}
        if(!(root instanceof Map))return reject("INVALID_REQUEST","Expected one tool request object");
        Map<String,Object> call=(Map<String,Object>)root;
        if(call.size()!=2 || !(call.get("tool") instanceof String) || !(call.get("arguments") instanceof Map))
            return reject("INVALID_REQUEST","Expected exactly tool and arguments");
        return parseCall((String)call.get("tool"),(Map<String,Object>)call.get("arguments"));
    }

    /** Validates an already-extracted tool name and raw arguments map (used by XML/Pythonic/JSON parsers). */
    public Parsed parseCall(String toolName, Map<String,Object> rawArgs){
        if(toolName==null || rawArgs==null)return reject("INVALID_REQUEST","Expected tool name and arguments");
        Tool selected=null;for(Tool tool:Tool.values())if(tool.wireName.equals(toolName))selected=tool;
        if(selected==null)return reject("UNKNOWN_TOOL","Tool is not allowlisted");
        Map<String,Object> args=new LinkedHashMap<>(rawArgs);
        try{
            switch(selected){
                case FLASHLIGHT:keys(args,"on");if(!(args.get("on") instanceof Boolean))bad("on must be boolean");break;
                case VOLUME:keys(args,"stream","percent");choice(args,"stream","music","alarm","ring","notification");integer(args,"percent",0,100);break;
                case TIMER:keys(args,"seconds","label");integer(args,"seconds",1,86400);label(args);break;
                case ALARM:keys(args,"hour","minute","label","days");integer(args,"hour",0,23);integer(args,"minute",0,59);label(args);days(args);break;
                case REMINDER:keys(args,"when_epoch_sec","when","title","body");
                    if(args.containsKey("when") && !args.containsKey("when_epoch_sec")){
                        args.put("when_epoch_sec",parseIsoToEpochSec(args.remove("when"),"when",false));
                        if(!args.containsKey("body"))args.put("body","");
                    }
                    if(!args.containsKey("when_epoch_sec") || !args.containsKey("title") || !args.containsKey("body"))
                        bad("Expected when_epoch_sec (or when), title, and body");
                    reminder(args,nowEpochSec.getAsLong());break;
                case SHOW_ALARMS:keys(args);break;
                case APPLICATION:keys(args,"package");String name=text(args,"package",1,255);
                    if(!name.matches("(?:[A-Za-z][A-Za-z0-9_]*\\.)+[A-Za-z][A-Za-z0-9_]*"))bad("Expected an installed package identifier");break;
                case SETTINGS:keys(args,"page");choice(args,"page","wifi","bluetooth","sound","display","application");break;
                case COPY:keys(args,"text");text(args,"text",0,4000);break;
                case SHARE:keys(args,"text");text(args,"text",1,4000);break;
                case CURRENT_TIME:keys(args,"timezone");timezone(args);break;
                case CALCULATOR:keys(args,"expression");text(args,"expression",1,200);break;
                case UNIT_CONVERT:keys(args,"value","from","to");number(args,"value");text(args,"from",1,32);text(args,"to",1,32);
                    UnitConverter.convert(((Number)args.get("value")).doubleValue(),(String)args.get("from"),(String)args.get("to"));break;
                case WEATHER:keys(args,"location","latitude","longitude");weather(args);break;
                case LIST_NOTIFICATIONS:keys(args,"limit","package");listNotifications(args);break;
                case DRAFT_MESSAGE: {
                    if(!args.containsKey("channel")) bad("channel is required");
                    String channel = text(args,"channel",1,10);
                    if("sms".equals(channel)){
                        keys(args,"channel","to","body");
                        String to = text(args,"to",1,64);
                        for(int i=0;i<to.length();i++){ if(Character.isISOControl(to.charAt(i))) bad("to contains invalid characters"); }
                        text(args,"body",1,2000);
                    } else if("share".equals(channel)){
                        if(!args.containsKey("package")) bad("package is required for share");
                        if(!args.containsKey("body")) bad("body is required");
                        String pkg = text(args,"package",1,255);
                        if(!ALLOWED_SHARE_PKGS.contains(pkg)) bad("share package is not allowlisted");
                        text(args,"body",1,4000);
                        if(args.containsKey("to")){
                            String to = text(args,"to",0,64);
                            for(int i=0;i<to.length();i++){ if(Character.isISOControl(to.charAt(i))) bad("to contains invalid characters"); }
                        } else {
                            args.put("to","");
                        }
                        Set<String> allowed = new HashSet<>(Arrays.asList("channel","package","body","to"));
                        if(!allowed.containsAll(args.keySet())) bad("Unexpected argument");
                    } else {
                        bad("channel must be sms or share");
                    }
                    break;
                }
                case SEND_MESSAGE: {
                    keys(args,"draft_id");
                    text(args,"draft_id",1,128);
                    break;
                }
                case LIST_EVENTS:keys(args,"window_start_epoch_sec","window_end_epoch_sec","window_start","window_end","limit");
                    if(args.containsKey("window_start") && !args.containsKey("window_start_epoch_sec")){
                        args.put("window_start_epoch_sec",parseIsoToEpochSec(args.remove("window_start"),"window_start",false));
                    }
                    if(args.containsKey("window_end") && !args.containsKey("window_end_epoch_sec")){
                        args.put("window_end_epoch_sec",parseIsoToEpochSec(args.remove("window_end"),"window_end",true));
                    }
                    if(!args.containsKey("limit")) args.put("limit",20L);
                    epochSec(args,"window_start_epoch_sec");epochSec(args,"window_end_epoch_sec");integer(args,"limit",1,50);
                    eventWindow(args,"window_start_epoch_sec","window_end_epoch_sec");break;
                case CREATE_EVENT:keys(args,"title","start_epoch_sec","end_epoch_sec","start","end","duration_minutes","calendar_id","all_day","location","notes");
                    textBounded(args,"title",1,200,true);
                    boolean dateOnlyStart=false;
                    if(args.containsKey("start") && !args.containsKey("start_epoch_sec")){
                        Object rawStart=args.remove("start");
                        dateOnlyStart=isDateOnlyIso(rawStart);
                        args.put("start_epoch_sec",parseIsoToEpochSec(rawStart,"start",false));
                    }
                    if(!args.containsKey("all_day")) args.put("all_day",dateOnlyStart);
                    if(!(args.get("all_day") instanceof Boolean))bad("all_day must be boolean");
                    boolean allDay=(Boolean)args.get("all_day");
                    if(args.containsKey("end") && !args.containsKey("end_epoch_sec")){
                        args.put("end_epoch_sec",parseIsoToEpochSec(args.remove("end"),"end",allDay));
                    }
                    if(!args.containsKey("end_epoch_sec") && args.containsKey("start_epoch_sec") && args.get("start_epoch_sec") instanceof Long){
                        long startVal=(Long)args.get("start_epoch_sec");
                        long durSec=allDay ? 86400L : 3600L;
                        if(args.containsKey("duration_minutes")){
                            integer(args,"duration_minutes",1,24*60*30);
                            durSec=((Integer)args.remove("duration_minutes")).longValue()*60L;
                        }
                        args.put("end_epoch_sec",startVal+durSec);
                    } else if(args.containsKey("duration_minutes")){
                        args.remove("duration_minutes");
                    }
                    epochSec(args,"start_epoch_sec");epochSec(args,"end_epoch_sec");eventRange(args,"start_epoch_sec","end_epoch_sec");
                    if(args.containsKey("calendar_id")) calendarId(args,"calendar_id");
                    else args.put("calendar_id","");
                    if(!args.containsKey("location")) args.put("location","");
                    if(!args.containsKey("notes")) args.put("notes","");
                    textBounded(args,"location",0,2000,false);textBounded(args,"notes",0,4000,false);break;
                case UPDATE_EVENT:keys(args,"event_id","title","start_epoch_sec","end_epoch_sec","start","end");
                    eventId(args,"event_id");textBounded(args,"title",1,200,true);
                    if(args.containsKey("start") && !args.containsKey("start_epoch_sec")){
                        args.put("start_epoch_sec",parseIsoToEpochSec(args.remove("start"),"start",false));
                    }
                    if(args.containsKey("end") && !args.containsKey("end_epoch_sec")){
                        args.put("end_epoch_sec",parseIsoToEpochSec(args.remove("end"),"end",false));
                    }
                    epochSec(args,"start_epoch_sec");epochSec(args,"end_epoch_sec");eventRange(args,"start_epoch_sec","end_epoch_sec");break;
                case DELETE_EVENT:keys(args,"event_id");eventId(args,"event_id");break;
                case WEB_SEARCH:keys(args,"query","time_range");
                    textBounded(args,"query",1,300,true);
                    if(!args.containsKey("time_range")) args.put("time_range","");
                    String range=text(args,"time_range",0,16);
                    if(!range.isEmpty() && !Arrays.asList("day","week","month","year").contains(range))
                        bad("time_range must be day, week, month, or year");
                    break;
                case SEARCH_TOOLS:keys(args,"query");
                    textBounded(args,"query",1,200,true);
                    break;
                case FS_LIST:keys(args,"path","max_depth");
                    if(!args.containsKey("path")) args.put("path","");
                    args.put("path",WorkspacePath.normalize(text(args,"path",0,512),true));
                    if(!args.containsKey("max_depth")) args.put("max_depth",2);
                    else integer(args,"max_depth",1,4);
                    break;
                case FS_SEARCH:keys(args,"query","path");
                    textBounded(args,"query",1,200,true);
                    if(!args.containsKey("path")) args.put("path","");
                    args.put("path",WorkspacePath.normalize(text(args,"path",0,512),true));
                    break;
                case FS_READ:keys(args,"path","start_line","max_lines");
                    args.put("path",WorkspacePath.normalize(text(args,"path",1,512),false));
                    if(!args.containsKey("start_line")) args.put("start_line",1);
                    else integer(args,"start_line",1,100000);
                    if(!args.containsKey("max_lines")) args.put("max_lines",120);
                    else integer(args,"max_lines",1,300);
                    break;
                case NOTE_CREATE:keys(args,"title","content","folder","tags");
                    textBounded(args,"title",1,120,true);
                    textBounded(args,"content",1,16000,false);
                    if(!args.containsKey("folder")) args.put("folder","");
                    args.put("folder",WorkspacePath.normalize(text(args,"folder",0,512),true));
                    if(!args.containsKey("tags")) args.put("tags","");
                    else if(args.get("tags") instanceof List){
                        StringBuilder joined=new StringBuilder();
                        for(Object item:(List<?>)args.get("tags")){
                            if(joined.length()>0)joined.append(',');
                            joined.append(String.valueOf(item));
                        }
                        args.put("tags",joined.toString());
                    }
                    textBounded(args,"tags",0,300,true);
                    break;
                case FS_APPEND:keys(args,"path","content");
                    args.put("path",WorkspacePath.normalize(text(args,"path",1,512),false));
                    textBounded(args,"content",1,8000,false);
                    break;
                case FS_EDIT:keys(args,"path","old_text","new_text");
                    args.put("path",WorkspacePath.normalize(text(args,"path",1,512),false));
                    textBounded(args,"old_text",1,4000,false);
                    textBounded(args,"new_text",0,4000,false);
                    break;
                case FS_DELETE:keys(args,"path");
                    args.put("path",WorkspacePath.normalize(text(args,"path",1,512),false));
                    break;
            }
        }catch(IllegalArgumentException e){return reject("INVALID_ARGUMENTS",e.getMessage());}
        return new Parsed(request(selected,args),"VALIDATED","Arguments validated; authorization still required");
    }
    private static void keys(Map<String,Object> args,String... allowed){
        Set<String> names=new HashSet<>(Arrays.asList(allowed));if(!names.containsAll(args.keySet()))bad("Unexpected argument");
    }
    private static void integer(Map<String,Object> args,String key,int low,int high){
        Object v=args.get(key);if(!(v instanceof Long) || (Long)v<low || (Long)v>high)bad(key+" must be an integer in "+low+".."+high);
        args.put(key,((Long)v).intValue());
    }
    private static void number(Map<String,Object> args,String key){
        Object v=args.get(key);
        if(v instanceof Long) args.put(key,((Long)v).doubleValue());
        else if(v instanceof Double) {
            double d=(Double)v;
            if(Double.isNaN(d) || Double.isInfinite(d)) bad(key+" must be a finite number");
        } else bad(key+" must be a number");
    }
    private static String text(Map<String,Object> args,String key,int min,int max){
        Object v=args.get(key);if(!(v instanceof String))bad(key+" must be text");String text=(String)v;
        if(text.length()<min || text.length()>max || text.indexOf('\0')>=0)bad(key+" length/content is invalid");return text;
    }
    private static void label(Map<String,Object> args){
        if(!args.containsKey("label"))args.put("label","");String label=text(args,"label",0,100);
        for(int i=0;i<label.length();i++)if(Character.isISOControl(label.charAt(i)) || label.charAt(i)=='\u2028' || label.charAt(i)=='\u2029')bad("label must be one line");
    }
    private static void timezone(Map<String,Object> args){
        if(!args.containsKey("timezone"))args.put("timezone","");
        String zone=text(args,"timezone",0,64);
        for(int i=0;i<zone.length();i++){
            char c=zone.charAt(i);
            if(Character.isISOControl(c) || c=='\u2028' || c=='\u2029')bad("timezone must be one line");
        }
        if(!zone.isEmpty()){
            try{java.time.ZoneId.of(zone);}catch(Exception e){bad("timezone is not a valid IANA zone");}
        }
    }
    @SuppressWarnings("unchecked")
    private static void days(Map<String,Object> args){
        if(!args.containsKey("days")){args.put("days",Collections.emptyList());return;}
        Object raw=args.get("days");
        if(!(raw instanceof List))bad("days must be an array of weekday integers");
        List<?> list=(List<?>)raw;
        if(list.size()>7)bad("days may list each weekday at most once");
        LinkedHashSet<Integer> unique=new LinkedHashSet<>();
        for(Object item:list){
            if(!(item instanceof Long) || (Long)item<1 || (Long)item>7)bad("days entries must be Calendar weekdays 1..7");
            if(!unique.add(((Long)item).intValue()))bad("days entries must be unique");
        }
        args.put("days",new ArrayList<>(unique));
    }
    private static void reminder(Map<String,Object> args,long nowEpochSec){
        Object w=args.get("when_epoch_sec");
        if(!(w instanceof Long))bad("when_epoch_sec must be an integer epoch second");
        long when=(Long)w;
        long min=nowEpochSec+60L;
        long max=nowEpochSec+366L*24L*3600L;
        if(when<nowEpochSec)bad("when_epoch_sec must not be in the past");
        if(when<min || when>max)bad("when_epoch_sec must be between now+60s and now+366 days");
        args.put("when_epoch_sec",when);
        if(!args.containsKey("title"))args.put("title","");
        if(!args.containsKey("body"))args.put("body","");
        String title=text(args,"title",0,100);
        String body=text(args,"body",0,500);
        oneLine(title,"title");noHtml(title,"title");reminderBody(body);
    }
    private static void oneLine(String value,String key){
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(Character.isISOControl(c) || c=='\u2028' || c=='\u2029')bad(key+" must be one line");
        }
    }
    private static void reminderBody(String value){
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c=='\0' || ((Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t')) || c=='\u2028' || c=='\u2029')
                bad("body contains invalid control characters");
        }
        noHtml(value,"body");
    }
    private static void noHtml(String value,String key){
        if(value.indexOf('<')>=0 && value.indexOf('>')>=0 && java.util.regex.Pattern.compile("(?s)<\\s*/?\\s*[A-Za-z!][^>]*>").matcher(value).find())
            bad(key+" must not contain HTML");
    }
    private static void weather(Map<String,Object> args){
        boolean hasLocation=args.containsKey("location");
        boolean hasLat=args.containsKey("latitude");
        boolean hasLon=args.containsKey("longitude");
        if(hasLocation){
            if(hasLat || hasLon)bad("Provide location or coordinates, not both");
            text(args,"location",1,100);
            String location=(String)args.get("location");
            for(int i=0;i<location.length();i++){
                char c=location.charAt(i);
                if(Character.isISOControl(c) || c=='\u2028' || c=='\u2029')bad("location must be one line");
            }
            return;
        }
        if(hasLat && hasLon){
            number(args,"latitude");number(args,"longitude");
            double lat=((Number)args.get("latitude")).doubleValue();
            double lon=((Number)args.get("longitude")).doubleValue();
            if(lat<-90 || lat>90 || lon<-180 || lon>180)bad("coordinates are out of range");
            return;
        }
        bad("Provide location or both latitude and longitude");
    }
    private static void listNotifications(Map<String,Object> args){
        if(!args.containsKey("limit")) args.put("limit",20L);
        if(!args.containsKey("package")) args.put("package","");
        integer(args,"limit",1,50);
        String name=text(args,"package",0,255);
        if(name.isEmpty()) return;
        if(!name.matches("(?:[A-Za-z][A-Za-z0-9_]*\\.)+[A-Za-z][A-Za-z0-9_]*")) bad("Expected an installed package identifier");
    }
    private static void epochSec(Map<String,Object> args,String key){
        Object v=args.get(key);
        if(!(v instanceof Long))bad(key+" must be an integer epoch seconds");
        long t=(Long)v;
        if(t<0 || t>4102444800L)bad(key+" is out of range");
        args.put(key,t);
    }
    private static void eventWindow(Map<String,Object> args,String startKey,String endKey){
        long start=((Number)args.get(startKey)).longValue();
        long end=((Number)args.get(endKey)).longValue();
        if(end<start)bad("window_end_epoch_sec must be >= window_start_epoch_sec");
        if(end-start>366L*24*60*60)bad("Event window is too large");
    }
    private static void eventRange(Map<String,Object> args,String startKey,String endKey){
        long start=((Number)args.get(startKey)).longValue();
        long end=((Number)args.get(endKey)).longValue();
        if(end<start)bad("end_epoch_sec must be >= start_epoch_sec");
        if(end-start>366L*24*60*60)bad("Event duration is too large");
    }
    private static void textBounded(Map<String,Object> args,String key,int min,int max,boolean singleLine){
        String value=text(args,key,min,max);
        if(singleLine){
            for(int i=0;i<value.length();i++){
                char c=value.charAt(i);
                if(Character.isISOControl(c) || c=='\u2028' || c=='\u2029')bad(key+" must be one line");
            }
        }else{
            for(int i=0;i<value.length();i++){
                char c=value.charAt(i);
                if(c=='\0' || (Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t'))bad(key+" contains invalid characters");
            }
        }
    }
    private static void eventId(Map<String,Object> args,String key){
        String id=text(args,key,1,128);
        for(int i=0;i<id.length();i++){
            char c=id.charAt(i);
            if(Character.isISOControl(c) || c=='\u2028' || c=='\u2029')bad(key+" is invalid");
        }
    }
    private static void calendarId(Map<String,Object> args,String key){
        String id=text(args,key,1,128);
        for(int i=0;i<id.length();i++){
            char c=id.charAt(i);
            if(Character.isISOControl(c) || c=='\u2028' || c=='\u2029')bad(key+" is invalid");
        }
    }
    private static boolean isDateOnlyIso(Object raw){
        if(!(raw instanceof String))return false;
        String s=((String)raw).trim();
        return s.matches("\\d{4}-\\d{2}-\\d{2}");
    }
    private static long parseIsoToEpochSec(Object raw,String key,boolean endOfDateIfDateOnly){
        if(raw instanceof Long)return (Long)raw;
        if(!(raw instanceof String))bad(key+" must be an ISO-8601 date/datetime string");
        String s=((String)raw).trim();
        if(s.isEmpty() || s.length()>64)bad(key+" is not a valid ISO-8601 datetime");
        try{
            if(s.matches("\\d{4}-\\d{2}-\\d{2}")){
                java.time.LocalDate date=java.time.LocalDate.parse(s);
                if(endOfDateIfDateOnly)date=date.plusDays(1);
                return date.atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond();
            }
            String normalized=s.contains(" ") && !s.contains("T") ? s.replaceFirst(" ","T") : s;
            if(normalized.endsWith("Z") || normalized.matches(".*[+-]\\d{2}:?\\d{2}$")){
                return java.time.OffsetDateTime.parse(normalized).toEpochSecond();
            }
            return java.time.LocalDateTime.parse(normalized)
                    .atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        }catch(Exception e){
            bad(key+" must be an ISO-8601 datetime like YYYY-MM-DDTHH:MM");
            return 0L;
        }
    }
    private static void choice(Map<String,Object> args,String key,String... values){
        if(!Arrays.asList(values).contains(args.get(key)))bad(key+" is unsupported");
    }
    private static void bad(String message){throw new IllegalArgumentException(message);}
}
