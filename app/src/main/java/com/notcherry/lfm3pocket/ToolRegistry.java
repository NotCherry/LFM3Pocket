package com.notcherry.lfm3pocket;

import java.util.*;

/** Validation produces data only. Authorization and Android execution are separate mandatory steps. */
public final class ToolRegistry {
    public enum Tool {
        FLASHLIGHT("toggle_flashlight"),VOLUME("set_volume"),TIMER("set_timer"),ALARM("set_alarm"),
        APPLICATION("open_application"),SETTINGS("open_settings"),COPY("copy_text"),SHARE("share_text");
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
        public final int hour,minute;public final String label;
        private AlarmRequest(Map<String,Object> a){super(Tool.ALARM,a);hour=(Integer)a.get("hour");minute=(Integer)a.get("minute");label=(String)a.get("label");}
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
    private static Request request(Tool tool,Map<String,Object> args){
        switch(tool){
            case FLASHLIGHT:return new FlashlightRequest(args);case VOLUME:return new VolumeRequest(args);
            case TIMER:return new TimerRequest(args);case ALARM:return new AlarmRequest(args);
            case APPLICATION:return new ApplicationRequest(args);case SETTINGS:return new SettingsRequest(args);
            case COPY:case SHARE:return new TextRequest(tool,args);
            default:throw new IllegalArgumentException("Unregistered tool");
        }
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
        Tool selected=null;for(Tool tool:Tool.values())if(tool.wireName.equals(call.get("tool")))selected=tool;
        if(selected==null)return reject("UNKNOWN_TOOL","Tool is not allowlisted");
        Map<String,Object> args=new LinkedHashMap<>((Map<String,Object>)call.get("arguments"));
        try{
            switch(selected){
                case FLASHLIGHT:keys(args,"on");if(!(args.get("on") instanceof Boolean))bad("on must be boolean");break;
                case VOLUME:keys(args,"stream","percent");choice(args,"stream","music","alarm","ring","notification");integer(args,"percent",0,100);break;
                case TIMER:keys(args,"seconds","label");integer(args,"seconds",1,86400);label(args);break;
                case ALARM:keys(args,"hour","minute","label");integer(args,"hour",0,23);integer(args,"minute",0,59);label(args);break;
                case APPLICATION:keys(args,"package");String name=text(args,"package",1,255);
                    if(!name.matches("(?:[A-Za-z][A-Za-z0-9_]*\\.)+[A-Za-z][A-Za-z0-9_]*"))bad("Expected an installed package identifier");break;
                case SETTINGS:keys(args,"page");choice(args,"page","wifi","bluetooth","sound","display","application");break;
                case COPY:keys(args,"text");text(args,"text",0,4000);break;
                case SHARE:keys(args,"text");text(args,"text",1,4000);break;
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
    private static String text(Map<String,Object> args,String key,int min,int max){
        Object v=args.get(key);if(!(v instanceof String))bad(key+" must be text");String text=(String)v;
        if(text.length()<min || text.length()>max || text.indexOf('\0')>=0)bad(key+" length/content is invalid");return text;
    }
    private static void label(Map<String,Object> args){
        if(!args.containsKey("label"))args.put("label","");String label=text(args,"label",0,100);
        for(int i=0;i<label.length();i++)if(Character.isISOControl(label.charAt(i)))bad("label must be one line");
    }
    private static void choice(Map<String,Object> args,String key,String... values){
        if(!Arrays.asList(values).contains(args.get(key)))bad(key+" is unsupported");
    }
    private static void bad(String message){throw new IllegalArgumentException(message);}
}
