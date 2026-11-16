package com.notcherry.lfm3pocket;

import java.util.*;

/** Validation produces data only. Authorization and Android execution are separate mandatory steps. */
public final class ToolRegistry {
    public enum Tool {
        FLASHLIGHT("toggle_flashlight"),VOLUME("set_volume"),TIMER("set_timer"),ALARM("set_alarm"),
        SHOW_ALARMS("show_alarms"),APPLICATION("open_application"),SETTINGS("open_settings"),
        COPY("copy_text"),SHARE("share_text"),CURRENT_TIME("current_time"),CALCULATOR("calculator"),
        UNIT_CONVERT("unit_convert"),WEATHER("weather");
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
    private static Request request(Tool tool,Map<String,Object> args){
        switch(tool){
            case FLASHLIGHT:return new FlashlightRequest(args);case VOLUME:return new VolumeRequest(args);
            case TIMER:return new TimerRequest(args);case ALARM:return new AlarmRequest(args);
            case SHOW_ALARMS:return new ShowAlarmsRequest(args);
            case APPLICATION:return new ApplicationRequest(args);case SETTINGS:return new SettingsRequest(args);
            case COPY:case SHARE:return new TextRequest(tool,args);
            case CURRENT_TIME:return new CurrentTimeRequest(args);
            case CALCULATOR:return new CalculatorRequest(args);
            case UNIT_CONVERT:return new UnitConvertRequest(args);
            case WEATHER:return new WeatherRequest(args);
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
                case ALARM:keys(args,"hour","minute","label","days");integer(args,"hour",0,23);integer(args,"minute",0,59);label(args);days(args);break;
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
    private static void choice(Map<String,Object> args,String key,String... values){
        if(!Arrays.asList(values).contains(args.get(key)))bad(key+" is unsupported");
    }
    private static void bad(String message){throw new IllegalArgumentException(message);}
}
