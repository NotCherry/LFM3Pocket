package com.notcherry.lfm3pocket;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Read-only helpers and the opt-in Open-Meteo weather tool. Messages are marked
 * untrusted so callers must not treat them as instructions for later turns.
 */
public final class BuiltinToolsExecutor implements AndroidActionExecutor {
    private final BooleanSupplier networkToolsEnabled;
    private final OpenMeteoWeather.Transport weatherTransport;

    public BuiltinToolsExecutor(BooleanSupplier networkToolsEnabled,
                                OpenMeteoWeather.Transport weatherTransport) {
        if (networkToolsEnabled == null || weatherTransport == null) {
            throw new IllegalArgumentException("Missing builtin tool dependencies");
        }
        this.networkToolsEnabled = networkToolsEnabled;
        this.weatherTransport = weatherTransport;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if ("current_time".equals(approvedToolName)) return currentTime(arguments);
        if ("calculator".equals(approvedToolName)) return calculator(arguments);
        if ("unit_convert".equals(approvedToolName)) return unitConvert(arguments);
        if ("weather".equals(approvedToolName)) return weather(arguments);
        return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
    }

    private ActionResult currentTime(Map<String, String> arguments) {
        if (arguments == null || arguments.size() > 1
                || (arguments.size() == 1 && !arguments.containsKey("timezone"))) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected optional timezone only.");
        }
        try {
            String timezone = arguments.get("timezone");
            return new ActionResult(true, "CURRENT_TIME", CurrentTimeTool.format(timezone == null ? "" : timezone));
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        }
    }

    private ActionResult calculator(Map<String, String> arguments) {
        if (arguments == null || arguments.size() != 1 || !arguments.containsKey("expression")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected expression.");
        }
        try {
            double value = SafeCalculator.evaluate(arguments.get("expression"));
            return new ActionResult(true, "CALCULATOR_RESULT",
                    "UNTRUSTED_CALC " + SafeCalculator.format(value));
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        }
    }

    private ActionResult unitConvert(Map<String, String> arguments) {
        if (arguments == null || arguments.size() != 3
                || !arguments.containsKey("value")
                || !arguments.containsKey("from")
                || !arguments.containsKey("to")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected value, from, and to.");
        }
        try {
            double value = Double.parseDouble(arguments.get("value"));
            double converted = UnitConverter.convert(value, arguments.get("from"), arguments.get("to"));
            return new ActionResult(true, "UNIT_CONVERT_RESULT",
                    "UNTRUSTED_UNITS " + UnitConverter.format(converted)
                            + " " + arguments.get("to"));
        } catch (NumberFormatException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "value must be a number");
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        }
    }

    private ActionResult weather(Map<String, String> arguments) {
        if (!networkToolsEnabled.getAsBoolean()) {
            return new ActionResult(false, "NETWORK_TOOLS_DISABLED",
                    "Network tools are off. Enable them in Settings to fetch weather.");
        }
        if (arguments == null) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected location or coordinates.");
        }
        try {
            String location = arguments.get("location");
            Double latitude = parseOptionalDouble(arguments.get("latitude"));
            Double longitude = parseOptionalDouble(arguments.get("longitude"));
            OpenMeteoWeather.Report report = OpenMeteoWeather.lookup(location, latitude, longitude, weatherTransport);
            return new ActionResult(true, "WEATHER_RESULT", report.summary);
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "Weather request failed" : e.getMessage();
            return new ActionResult(false, "WEATHER_UNAVAILABLE",
                    "Weather is unavailable offline or the request failed: " + detail);
        }
    }

    private static Double parseOptionalDouble(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        return Double.valueOf(raw);
    }
}
