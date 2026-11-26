package com.notcherry.lfm3pocket;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Read-only helpers and the opt-in Open-Meteo weather tool. Messages are marked
 * untrusted so callers must not treat them as instructions for later turns.
 */
public final class BuiltinToolsExecutor implements AndroidActionExecutor {
    public interface WebSearchRunner {
        java.util.List<WebSearch.Source> search(String endpoint, String query, String timeRange) throws Exception;
    }

    interface SearchConfigRunner {
        java.util.List<WebSearch.Source> search(SearchConfig config, String query, String timeRange) throws Exception;
    }

    private final BooleanSupplier networkToolsEnabled;
    private final OpenMeteoWeather.Transport weatherTransport;
    private final java.util.function.Supplier<SearchConfig> searchConfigSupplier;
    private final SearchConfigRunner searchConfigRunner;

    public BuiltinToolsExecutor(BooleanSupplier networkToolsEnabled,
                                OpenMeteoWeather.Transport weatherTransport) {
        this(networkToolsEnabled, weatherTransport, () -> false, () -> "", WebSearch::search);
    }

    public BuiltinToolsExecutor(BooleanSupplier networkToolsEnabled,
                                OpenMeteoWeather.Transport weatherTransport,
                                BooleanSupplier webSearchEnabled,
                                java.util.function.Supplier<String> webSearchEndpoint,
                                WebSearchRunner webSearchRunner) {
        this(networkToolsEnabled, weatherTransport,
                () -> SearchConfig.forSearxng(
                        webSearchEndpoint == null ? "" : webSearchEndpoint.get(),
                        webSearchEnabled != null && webSearchEnabled.getAsBoolean()),
                (cfg, query, timeRange) -> {
                    WebSearchRunner runner = webSearchRunner == null ? WebSearch::search : webSearchRunner;
                    return runner.search(cfg.endpoint, query, timeRange);
                });
    }

    BuiltinToolsExecutor(BooleanSupplier networkToolsEnabled,
                         OpenMeteoWeather.Transport weatherTransport,
                         java.util.function.Supplier<SearchConfig> searchConfigSupplier,
                         SearchConfigRunner searchConfigRunner) {
        if (networkToolsEnabled == null || weatherTransport == null) {
            throw new IllegalArgumentException("Missing builtin tool dependencies");
        }
        this.networkToolsEnabled = networkToolsEnabled;
        this.weatherTransport = weatherTransport;
        this.searchConfigSupplier = searchConfigSupplier == null
                ? () -> new SearchConfig(SearchProvider.SEARXNG, "", "", false) : searchConfigSupplier;
        this.searchConfigRunner = searchConfigRunner == null ? WebSearch::search : searchConfigRunner;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if ("current_time".equals(approvedToolName)) return currentTime(arguments);
        if ("calculator".equals(approvedToolName)) return calculator(arguments);
        if ("unit_convert".equals(approvedToolName)) return unitConvert(arguments);
        if ("weather".equals(approvedToolName)) return weather(arguments);
        if ("web_search".equals(approvedToolName)) return webSearch(arguments);
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

    private ActionResult webSearch(Map<String, String> arguments) {
        SearchConfig config = searchConfigSupplier.get();
        if (config == null || !config.isReady()) {
            return new ActionResult(false, "WEB_SEARCH_DISABLED",
                    "Web search is off. Configure a search provider and enable Web search in Settings.");
        }
        if (arguments == null || !arguments.containsKey("query")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected query.");
        }
        String query = arguments.get("query");
        String timeRange = arguments.get("time_range");
        if (query == null || query.trim().isEmpty()) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "query is required.");
        }
        try {
            java.util.List<WebSearch.Source> sources = searchConfigRunner.search(
                    config, query.trim(), timeRange == null ? "" : timeRange.trim());
            if (sources == null || sources.isEmpty()) {
                return new ActionResult(true, "WEB_SEARCH_EMPTY",
                        "UNTRUSTED_WEB_SEARCH No search results found for: " + query.trim());
            }
            StringBuilder out = new StringBuilder("UNTRUSTED_WEB_SEARCH results");
            for (int i = 0; i < sources.size(); i++) {
                WebSearch.Source s = sources.get(i);
                out.append('\n').append('[').append(i + 1).append("] ")
                        .append(s.title == null ? "" : s.title.replace('\n', ' '))
                        .append(" — ").append(s.snippet == null ? "" : s.snippet.replace('\n', ' '))
                        .append(" (").append(s.url == null ? "" : s.url).append(')');
            }
            return new ActionResult(true, "WEB_SEARCH_RESULT", out.toString());
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "Search request failed" : e.getMessage();
            return new ActionResult(false, "WEB_SEARCH_FAILED",
                    "Web search failed: " + detail);
        }
    }

    private static Double parseOptionalDouble(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        return Double.valueOf(raw);
    }
}
