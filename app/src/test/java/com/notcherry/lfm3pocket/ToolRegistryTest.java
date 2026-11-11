package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ToolRegistryTest {
    private final ToolRegistry registry=new ToolRegistry();
    @Test public void fixedRegistryAcceptsTypedArgumentsForEverySupportedTool(){
        String[] calls={"{\"tool\":\"toggle_flashlight\",\"arguments\":{\"on\":true}}",
            "{\"tool\":\"set_volume\",\"arguments\":{\"stream\":\"music\",\"percent\":45}}",
            "{\"tool\":\"set_timer\",\"arguments\":{\"seconds\":60,\"label\":\"Tea\"}}",
            "{\"tool\":\"set_alarm\",\"arguments\":{\"hour\":7,\"minute\":30}}",
            "{\"tool\":\"open_application\",\"arguments\":{\"package\":\"com.example.app\"}}",
            "{\"tool\":\"open_settings\",\"arguments\":{\"page\":\"wifi\"}}",
            "{\"tool\":\"copy_text\",\"arguments\":{\"text\":\"Zażółć 🐈\\nLocal\"}}",
            "{\"tool\":\"share_text\",\"arguments\":{\"text\":\"Hello\"}}"};
        Set<ToolRegistry.Tool> tools=new HashSet<>();
        for(String call:calls){ToolRegistry.Parsed p=registry.parse(call);assertTrue(p.status,p.isValid());tools.add(p.request.tool);}
        assertEquals(8,tools.size());
        assertEquals(60,((ToolRegistry.TimerRequest)registry.parse(calls[2]).request).seconds);
        assertEquals("",registry.parse(calls[3]).request.arguments.get("label"));
    }
    @Test public void unknownToolsAndMalformedJsonReturnNoExecutableRequest(){
        String[] bad={"",null,"rm -rf /","```json {} ```","[]","{}",
            "{tool:'set_timer',arguments:{seconds:60}}",
            "{\"tool\":\"set_timer\",\"arguments\":{\"seconds\":60,}}",
            "{\"tool\":\"set_timer\",\"tool\":\"set_alarm\",\"arguments\":{}}",
            "{\"tool\":\"set_timer\",\"arguments\":{\"seconds\":60,\"seconds\":1}}",
            "{\"tool\":\"shell\",\"arguments\":{\"command\":\"reboot\"}}",
            "{\"tool\":\"set_timer\",\"arguments\":{\"seconds\":60}} {}"};
        for(String call:bad){ToolRegistry.Parsed p=registry.parse(call);assertFalse(String.valueOf(call),p.isValid());assertNull(p.request);assertNotNull(p.message);}
        assertEquals("UNKNOWN_TOOL",registry.parse("{\"tool\":\"shell\",\"arguments\":{}}").status);
    }
    @Test public void invalidTypesRangesExtraKeysAndIntentInjectionAreRejected(){
        String[] args={"{\"seconds\":0}","{\"seconds\":86401}","{\"seconds\":1.5}","{\"seconds\":\"60\"}",
                "{\"seconds\":true}","{\"seconds\":null}","{\"seconds\":60,\"command\":\"reboot\"}"};
        for(String a:args)assertFalse(registry.parse("{\"tool\":\"set_timer\",\"arguments\":"+a+"}").isValid());
        assertFalse(registry.parse("{\"tool\":\"toggle_flashlight\",\"arguments\":{\"on\":\"true\"}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"open_application\",\"arguments\":{\"package\":\"intent:#Intent;end\"}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"open_settings\",\"arguments\":{\"page\":\"root_shell\"}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"set_alarm\",\"arguments\":{\"hour\":24,\"minute\":0}}").isValid());
    }
    @Test public void requestsAreImmutableAndEscapedUnicodeIsPreserved(){
        ToolRegistry.Parsed p=registry.parse("{\"tool\":\"copy_text\",\"arguments\":{\"text\":\"\\uD83D\\uDC08\"}}");
        assertTrue(p.isValid());assertEquals("🐈",p.request.arguments.get("text"));
        try{p.request.arguments.put("text","changed");fail();}catch(UnsupportedOperationException expected){}
        assertFalse(registry.parse("{\"tool\":\"copy_text\",\"arguments\":{\"text\":\"\\uD800\"}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"copy_text\",\"arguments\":{\"text\":\"\\u００７４\"}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"copy_text\",\"arguments\":{\"text\":\"\\u0000\"}}").isValid());
    }
    @Test public void labelsCannotSmuggleUnicodeLineSeparators(){
        for(String separator:new String[]{"\u2028","\u2029","\n"}) {
            String escaped=separator.equals("\n")?"\\n":separator;
            assertFalse(registry.parse("{\"tool\":\"set_timer\",\"arguments\":{\"seconds\":60,\"label\":\"Tea"+escaped+"hidden\"}}").isValid());
        }
        assertTrue(registry.parse("{\"tool\":\"copy_text\",\"arguments\":{\"text\":\"Line 1\\nLine 2\"}}").isValid());
    }
    @Test public void oversizedDeepAndRandomHostileInputFailsWithoutThrowing(){
        assertFalse(registry.parse(new String(new char[8193]).replace('\0','x')).isValid());
        assertFalse(registry.parse("{\"tool\":\"copy_text\",\"arguments\":{\"text\":[[[[[[[]]]]]]]}}").isValid());
        Random random=new Random(33);
        for(int i=0;i<1000;i++){
            StringBuilder s=new StringBuilder();for(int j=0,n=random.nextInt(128);j<n;j++)s.append((char)(32+random.nextInt(96)));
            ToolRegistry.Parsed parsed=registry.parse(s.toString());assertNotNull(parsed.status);
        }
    }
}
