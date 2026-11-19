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
            "{\"tool\":\"show_alarms\",\"arguments\":{}}",
            "{\"tool\":\"open_application\",\"arguments\":{\"package\":\"com.example.app\"}}",
            "{\"tool\":\"open_settings\",\"arguments\":{\"page\":\"wifi\"}}",
            "{\"tool\":\"copy_text\",\"arguments\":{\"text\":\"Zażółć 🐈\\nLocal\"}}",
            "{\"tool\":\"share_text\",\"arguments\":{\"text\":\"Hello\"}}",
            "{\"tool\":\"draft_message\",\"arguments\":{\"channel\":\"sms\",\"to\":\"+48500111222\",\"body\":\"Hi\"}}",
            "{\"tool\":\"send_message\",\"arguments\":{\"draft_id\":\"abc-123\"}}",
            "{\"tool\":\"current_time\",\"arguments\":{}}",
            "{\"tool\":\"calculator\",\"arguments\":{\"expression\":\"(2+3)*4\"}}",
            "{\"tool\":\"unit_convert\",\"arguments\":{\"value\":1,\"from\":\"km\",\"to\":\"m\"}}",
            "{\"tool\":\"weather\",\"arguments\":{\"location\":\"Warsaw\"}}",
            "{\"tool\":\"list_notifications\",\"arguments\":{\"limit\":20,\"package\":\"\"}}",
            "{\"tool\":\"list_events\",\"arguments\":{\"window_start_epoch_sec\":1731700000,\"window_end_epoch_sec\":1731786400,\"limit\":20}}",
            "{\"tool\":\"create_event\",\"arguments\":{\"title\":\"Standup\",\"start_epoch_sec\":1731744000,\"end_epoch_sec\":1731745800,\"calendar_id\":\"3\",\"all_day\":false,\"location\":\"\",\"notes\":\"\"}}",
            "{\"tool\":\"update_event\",\"arguments\":{\"event_id\":\"42\",\"title\":\"Standup moved\",\"start_epoch_sec\":1731745800,\"end_epoch_sec\":1731747600}}",
            "{\"tool\":\"delete_event\",\"arguments\":{\"event_id\":\"42\"}}"};
        Set<ToolRegistry.Tool> tools=new HashSet<>();
        for(String call:calls){ToolRegistry.Parsed p=registry.parse(call);assertTrue(p.status+": "+p.message,p.isValid());tools.add(p.request.tool);}
        assertEquals(20,tools.size());
        assertEquals(60,((ToolRegistry.TimerRequest)registry.parse("{\"tool\":\"set_timer\",\"arguments\":{\"seconds\":60,\"label\":\"Tea\"}}").request).seconds);
        assertEquals("",registry.parse("{\"tool\":\"set_alarm\",\"arguments\":{\"hour\":7,\"minute\":30}}").request.arguments.get("label"));
        ToolRegistry.AlarmRequest alarm=(ToolRegistry.AlarmRequest)registry.parse(
                "{\"tool\":\"set_alarm\",\"arguments\":{\"hour\":7,\"minute\":0,\"days\":[2,3,4,5,6]}}").request;
        assertEquals(Arrays.asList(2,3,4,5,6),alarm.days);
        assertFalse(registry.parse("{\"tool\":\"set_alarm\",\"arguments\":{\"hour\":7,\"minute\":0,\"days\":[0]}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"weather\",\"arguments\":{}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"unit_convert\",\"arguments\":{\"value\":1,\"from\":\"km\",\"to\":\"celsius\"}}").isValid());
    }

    @Test public void calendarToolsRejectInvalidWindowsIdsAndBounds(){
        assertTrue(registry.parse("{\"tool\":\"list_events\",\"arguments\":{\"window_start_epoch_sec\":0,\"window_end_epoch_sec\":0,\"limit\":1}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"list_events\",\"arguments\":{\"window_start_epoch_sec\":10,\"window_end_epoch_sec\":9,\"limit\":1}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"list_events\",\"arguments\":{\"window_start_epoch_sec\":0,\"window_end_epoch_sec\":32000000,\"limit\":1}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"list_events\",\"arguments\":{\"window_start_epoch_sec\":0,\"window_end_epoch_sec\":60,\"limit\":0}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"list_events\",\"arguments\":{\"window_start_epoch_sec\":0,\"window_end_epoch_sec\":60,\"limit\":51}}").isValid());
        String create="{\"tool\":\"create_event\",\"arguments\":{\"title\":\"T\",\"start_epoch_sec\":1,\"end_epoch_sec\":2,\"calendar_id\":\"cal\",\"all_day\":false,\"location\":\"\",\"notes\":\"\"}}";
        assertTrue(registry.parse(create).isValid());
        assertFalse(registry.parse(create.replace("\"calendar_id\":\"cal\"","\"calendar_id\":\"\"")).isValid());
        assertFalse(registry.parse(create.replace("\"all_day\":false","\"all_day\":\"false\"")).isValid());
        assertFalse(registry.parse("{\"tool\":\"create_event\",\"arguments\":{\"title\":\"T\",\"start_epoch_sec\":5,\"end_epoch_sec\":4,\"calendar_id\":\"1\",\"all_day\":false,\"location\":\"\",\"notes\":\"\"}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"update_event\",\"arguments\":{\"event_id\":\"\",\"title\":\"T\",\"start_epoch_sec\":1,\"end_epoch_sec\":2}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"delete_event\",\"arguments\":{\"event_id\":\"\\n\"}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"update_event\",\"arguments\":{\"event_id\":\"1\",\"title\":\"\",\"start_epoch_sec\":1,\"end_epoch_sec\":2}}").isValid());
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
    @Test public void draftMessageSchemaAndAllowlistValidation(){
        // sms ok
        assertTrue(registry.parse("{\"tool\":\"draft_message\",\"arguments\":{\"channel\":\"sms\",\"to\":\"+123\",\"body\":\"hi\"}}").isValid());
        // sms missing to
        assertFalse(registry.parse("{\"tool\":\"draft_message\",\"arguments\":{\"channel\":\"sms\",\"body\":\"hi\"}}").isValid());
        // share ok (allowlisted)
        assertTrue(registry.parse("{\"tool\":\"draft_message\",\"arguments\":{\"channel\":\"share\",\"package\":\"com.whatsapp\",\"body\":\"hi\"}}").isValid());
        assertTrue(registry.parse("{\"tool\":\"draft_message\",\"arguments\":{\"channel\":\"share\",\"package\":\"org.telegram.messenger\",\"body\":\"hi\",\"to\":\"\"}}").isValid());
        // share disallowed package
        assertFalse(registry.parse("{\"tool\":\"draft_message\",\"arguments\":{\"channel\":\"share\",\"package\":\"com.bad.app\",\"body\":\"hi\"}}").isValid());
        // bad channel
        assertFalse(registry.parse("{\"tool\":\"draft_message\",\"arguments\":{\"channel\":\"email\",\"to\":\"a\",\"body\":\"hi\"}}").isValid());
        // send_message needs draft_id
        assertTrue(registry.parse("{\"tool\":\"send_message\",\"arguments\":{\"draft_id\":\"x\"}}").isValid());
        assertFalse(registry.parse("{\"tool\":\"send_message\",\"arguments\":{}}").isValid());
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
