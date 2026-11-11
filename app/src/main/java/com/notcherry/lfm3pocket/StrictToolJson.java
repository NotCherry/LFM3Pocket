package com.notcherry.lfm3pocket;

import java.util.*;

/** Small bounded JSON reader for the tool protocol; duplicate keys and malformed Unicode fail closed. */
final class StrictToolJson {
    private final String input;private int position;
    private StrictToolJson(String input){this.input=input;}
    static Object parse(String text){
        if(text==null || text.length()>8192)throw new IllegalArgumentException();
        StrictToolJson parser=new StrictToolJson(text);Object value=parser.value(0);parser.space();
        if(parser.position!=text.length())throw new IllegalArgumentException();return value;
    }
    private Object value(int depth){
        if(depth>4)throw new IllegalArgumentException();space();char c=peek();
        if(c=='"')return string();
        if(c=='{'){
            position++;Map<String,Object> map=new LinkedHashMap<>();space();if(take('}'))return map;
            do{space();String key=string();space();require(':');
                if(map.containsKey(key) || map.size()>=64)throw new IllegalArgumentException();
                map.put(key,value(depth+1));space();if(take('}'))return map;require(',');
            }while(true);
        }
        if(c=='['){position++;List<Object> list=new ArrayList<>();space();if(take(']'))return list;
            do{if(list.size()>=64)throw new IllegalArgumentException();list.add(value(depth+1));space();
                if(take(']'))return list;require(',');}while(true);
        }
        for(String literal:new String[]{"true","false","null"})if(input.startsWith(literal,position)){
            position+=literal.length();return literal.equals("null")?null:literal.equals("true");
        }
        int start=position;
        while(position<input.length() && "-+0123456789.eE".indexOf(input.charAt(position))>=0)position++;
        String number=input.substring(start,position);
        if(number.length()>24 || !number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IllegalArgumentException();
        if(number.indexOf('.')>=0 || number.indexOf('e')>=0 || number.indexOf('E')>=0){
            double n=Double.parseDouble(number);if(!Double.isFinite(n))throw new IllegalArgumentException();return n;
        }
        return Long.parseLong(number);
    }
    private String string(){
        require('"');StringBuilder out=new StringBuilder();boolean ended=false;
        while(position<input.length()){
            char c=input.charAt(position++);if(c=='"'){ended=true;break;}if(c<32)throw new IllegalArgumentException();
            if(c=='\\'){
                c=read();switch(c){
                    case '"':case '\\':case '/':break;
                    case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;
                    case 'r':c='\r';break;case 't':c='\t';break;
                    case 'u':
                        int code=0;for(int i=0;i<4;i++){char digit=read();int hex=Character.digit(digit,16);if(hex<0 || digit>127)throw new IllegalArgumentException();code=code*16+hex;}
                        c=(char)code;break;
                    default:throw new IllegalArgumentException();
                }
            }
            out.append(c);
        }
        if(!ended)throw new IllegalArgumentException();
        for(int i=0;i<out.length();i++){
            char c=out.charAt(i);
            if(Character.isHighSurrogate(c)){
                if(++i>=out.length() || !Character.isLowSurrogate(out.charAt(i)))throw new IllegalArgumentException();
            }else if(Character.isLowSurrogate(c))throw new IllegalArgumentException();
        }
        return out.toString();
    }
    private char read(){if(position>=input.length())throw new IllegalArgumentException();return input.charAt(position++);}
    private char peek(){if(position>=input.length())throw new IllegalArgumentException();return input.charAt(position);}
    private void space(){while(position<input.length() && " \t\r\n".indexOf(input.charAt(position))>=0)position++;}
    private boolean take(char c){if(position<input.length() && input.charAt(position)==c){position++;return true;}return false;}
    private void require(char c){if(!take(c))throw new IllegalArgumentException();}
}
