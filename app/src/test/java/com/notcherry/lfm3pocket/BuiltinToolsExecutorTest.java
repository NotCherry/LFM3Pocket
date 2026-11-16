package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public final class BuiltinToolsExecutorTest {
    @Test public void calculatorAndUnitsWorkWithoutNetwork() {
        BuiltinToolsExecutor executor = new BuiltinToolsExecutor(() -> false, url -> {
            throw new AssertionError("network should stay off");
        });
        Map<String, String> calc = Collections.singletonMap("expression", "10/4");
        assertEquals("CALCULATOR_RESULT", executor.execute("calculator", calc).status);
        assertTrue(executor.execute("calculator", calc).message.startsWith("UNTRUSTED_CALC"));

        Map<String, String> units = new LinkedHashMap<>();
        units.put("value", "1");
        units.put("from", "km");
        units.put("to", "m");
        assertEquals("UNIT_CONVERT_RESULT", executor.execute("unit_convert", units).status);
        assertTrue(executor.execute("current_time", Collections.emptyMap()).succeeded);
    }

    @Test public void weatherHonoursNetworkToggleAndFailsGracefully() {
        AtomicBoolean enabled = new AtomicBoolean(false);
        BuiltinToolsExecutor executor = new BuiltinToolsExecutor(enabled::get, url -> {
            throw new java.net.UnknownHostException("offline");
        });
        Map<String, String> args = Collections.singletonMap("location", "Warsaw");
        assertEquals("NETWORK_TOOLS_DISABLED", executor.execute("weather", args).status);

        enabled.set(true);
        assertEquals("WEATHER_UNAVAILABLE", executor.execute("weather", args).status);

        BuiltinToolsExecutor online = new BuiltinToolsExecutor(() -> true, url -> {
            if (url.contains("geocoding")) {
                return "{\"results\":[{\"name\":\"Warsaw\",\"country\":\"Poland\",\"latitude\":52.23,\"longitude\":21.01}]}";
            }
            return "{\"current\":{\"temperature_2m\":11.0,\"weather_code\":0,\"wind_speed_10m\":4.0}}";
        });
        AndroidActionExecutor.ActionResult result = online.execute("weather", args);
        assertTrue(result.succeeded);
        assertTrue(result.message.startsWith("UNTRUSTED_WEATHER"));
    }
}
