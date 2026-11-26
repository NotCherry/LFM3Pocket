package com.notcherry.lfm3pocket;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Builds Liquid AI LFM3 native {@code <tools>...</tools>} system prompts, deferred tool search
 * results ({@code <additional_tools>...</additional_tools>}), and compact per-turn grounding
 * context (local clock, timezone, enabled capabilities).
 *
 * <p>Keeping the core tool catalog small and static in the system prompt preserves {@code native.cpp}
 * KV-cache prefix reuse across turns while allowing dozens of tools (OS actions, workspace vault,
 * calendar, custom shortcuts, MCP servers) to be discovered on demand via {@code search_tools}.
 */
public final class LfmToolCatalog {
    public static final class ToolSpec {
        public final String name;
        public final String schemaJson;
        public final List<String> parameterKeys;
        public final String keywords;
        public final boolean deferred;
        private final Predicate<Capabilities> enabledCheck;

        public ToolSpec(String name, String schemaJson, List<String> parameterKeys,
                        String keywords, boolean deferred, Predicate<Capabilities> enabledCheck) {
            this.name = name;
            this.schemaJson = schemaJson;
            this.parameterKeys = parameterKeys == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(parameterKeys));
            this.keywords = keywords == null ? "" : keywords.toLowerCase(Locale.ROOT);
            this.deferred = deferred;
            this.enabledCheck = enabledCheck == null ? c -> true : enabledCheck;
        }

        public boolean isEnabled(Capabilities caps) {
            return enabledCheck.test(caps);
        }
    }

    public static final class SearchResult {
        public final List<ToolSpec> matched;
        public final String formattedToolResponse;

        SearchResult(List<ToolSpec> matched, String formattedToolResponse) {
            this.matched = Collections.unmodifiableList(new ArrayList<>(matched));
            this.formattedToolResponse = formattedToolResponse;
        }
    }

    public static final class Capabilities {
        public final boolean webSearchEnabled;
        public final boolean weatherEnabled;
        public final boolean calendarReadReady;
        public final boolean calendarWriteReady;
        public final String pickedCalendarLabel;
        public final boolean notificationsEnabled;
        public final boolean workspaceReady;
        public final String workspaceLabel;
        public final List<ToolSpec> extraTools;

        public Capabilities(boolean webSearchEnabled, boolean weatherEnabled,
                            boolean calendarReadReady, boolean calendarWriteReady,
                            String pickedCalendarLabel, boolean notificationsEnabled) {
            this(webSearchEnabled, weatherEnabled, calendarReadReady, calendarWriteReady,
                    pickedCalendarLabel, notificationsEnabled, false, "", Collections.emptyList());
        }

        public Capabilities(boolean webSearchEnabled, boolean weatherEnabled,
                            boolean calendarReadReady, boolean calendarWriteReady,
                            String pickedCalendarLabel, boolean notificationsEnabled,
                            boolean workspaceReady, String workspaceLabel) {
            this(webSearchEnabled, weatherEnabled, calendarReadReady, calendarWriteReady,
                    pickedCalendarLabel, notificationsEnabled, workspaceReady, workspaceLabel,
                    Collections.emptyList());
        }

        public Capabilities(boolean webSearchEnabled, boolean weatherEnabled,
                            boolean calendarReadReady, boolean calendarWriteReady,
                            String pickedCalendarLabel, boolean notificationsEnabled,
                            boolean workspaceReady, String workspaceLabel,
                            List<ToolSpec> extraTools) {
            this.webSearchEnabled = webSearchEnabled;
            this.weatherEnabled = weatherEnabled;
            this.calendarReadReady = calendarReadReady;
            this.calendarWriteReady = calendarWriteReady;
            this.pickedCalendarLabel = pickedCalendarLabel == null ? "" : pickedCalendarLabel.trim();
            this.notificationsEnabled = notificationsEnabled;
            this.workspaceReady = workspaceReady;
            this.workspaceLabel = workspaceLabel == null ? "" : workspaceLabel.trim();
            this.extraTools = extraTools == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(extraTools));
        }

        public static Capabilities offlineDefaults() {
            return new Capabilities(false, false, false, false, "", false, false, "", Collections.emptyList());
        }
    }

    private static final DateTimeFormatter CONTEXT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd (EEE) HH:mm z", Locale.ROOT);

    private static final List<ToolSpec> BUILTIN_SPECS = Collections.unmodifiableList(Arrays.asList(
            // --- Always-loaded core tools (small static prefix for hot KV cache) ---
            new ToolSpec("search_tools",
                    "{\"name\":\"search_tools\",\"description\":\"Search available deferred tools (device controls, calendar edit/list, notes/files, reminders, messaging, weather, shortcuts, MCP) by keyword\",\"parameters\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Keywords describing the needed capability\"}},\"required\":[\"query\"]}}",
                    Collections.singletonList("query"),
                    "search tools discover capabilities find tool szukaj narzedzia", false, c -> true),
            new ToolSpec("web_search",
                    "{\"name\":\"web_search\",\"description\":\"Search the web for current information\",\"parameters\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Concise search query\"},\"time_range\":{\"type\":\"string\",\"enum\":[\"day\",\"week\",\"month\",\"year\"]}},\"required\":[\"query\"]}}",
                    Arrays.asList("query", "time_range"),
                    "web search internet news lookup wyszukaj siec aktualnosci", false, c -> c.webSearchEnabled),
            new ToolSpec("current_time",
                    "{\"name\":\"current_time\",\"description\":\"Get current local date and time\",\"parameters\":{\"type\":\"object\",\"properties\":{\"timezone\":{\"type\":\"string\",\"description\":\"Optional IANA timezone\"}}}}",
                    Collections.singletonList("timezone"),
                    "time clock date timezone godzina czas data strefa", false, c -> true),
            new ToolSpec("set_timer",
                    "{\"name\":\"set_timer\",\"description\":\"Start a countdown timer\",\"parameters\":{\"type\":\"object\",\"properties\":{\"seconds\":{\"type\":\"integer\"},\"label\":{\"type\":\"string\"}},\"required\":[\"seconds\"]}}",
                    Arrays.asList("seconds", "label"),
                    "timer countdown minutnik stoper odliczanie", false, c -> true),
            new ToolSpec("create_event",
                    "{\"name\":\"create_event\",\"description\":\"Create a calendar event in the user's picked calendar\",\"parameters\":{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"start\":{\"type\":\"string\",\"description\":\"Local ISO datetime YYYY-MM-DDTHH:MM or YYYY-MM-DD\"},\"end\":{\"type\":\"string\",\"description\":\"Optional end YYYY-MM-DDTHH:MM (defaults to +1h)\"},\"all_day\":{\"type\":\"boolean\"},\"location\":{\"type\":\"string\"},\"notes\":{\"type\":\"string\"}},\"required\":[\"title\",\"start\"]}}",
                    Arrays.asList("title", "start", "end", "all_day", "location", "notes", "calendar_id", "duration_minutes"),
                    "calendar event schedule meeting appointment kalendarz wydarzenie spotkanie", false, c -> true),
            new ToolSpec("fs_search",
                    "{\"name\":\"fs_search\",\"description\":\"Search filenames and text inside the user's local workspace/notes folder\",\"parameters\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"path\":{\"type\":\"string\",\"description\":\"Optional subfolder\"}},\"required\":[\"query\"]}}",
                    Arrays.asList("query", "path"),
                    "workspace files notes search grep vault obsidian markdown szukaj pliki notatki", false, c -> c.workspaceReady),
            new ToolSpec("note_create",
                    "{\"name\":\"note_create\",\"description\":\"Create a new Markdown note in the user's local workspace folder\",\"parameters\":{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"folder\":{\"type\":\"string\"},\"tags\":{\"type\":\"string\",\"description\":\"Comma-separated tags\"}},\"required\":[\"title\",\"content\"]}}",
                    Arrays.asList("title", "content", "folder", "tags"),
                    "note create markdown write save file vault obsidian notatka utworz zapisz plik", false, c -> c.workspaceReady),

            // --- Deferred tools (discoverable via search_tools -> <additional_tools>) ---
            new ToolSpec("fs_list",
                    "{\"name\":\"fs_list\",\"defer_loading\":true,\"description\":\"List files and folders inside the local workspace\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"max_depth\":{\"type\":\"integer\"}}}}",
                    Arrays.asList("path", "max_depth"),
                    "list files folder directory tree workspace vault obsidian lista pliki katalog folder", true, c -> c.workspaceReady),
            new ToolSpec("fs_read",
                    "{\"name\":\"fs_read\",\"defer_loading\":true,\"description\":\"Read numbered lines from a text/markdown file in the local workspace\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"start_line\":{\"type\":\"integer\"},\"max_lines\":{\"type\":\"integer\"}},\"required\":[\"path\"]}}",
                    Arrays.asList("path", "start_line", "max_lines"),
                    "read file note open markdown workspace vault obsidian czytaj plik notatka", true, c -> c.workspaceReady),
            new ToolSpec("fs_append",
                    "{\"name\":\"fs_append\",\"defer_loading\":true,\"description\":\"Append text to the end of an existing file in the local workspace\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}}",
                    Arrays.asList("path", "content"),
                    "append add line note file workspace vault dopisz dodaj plik notatka", true, c -> c.workspaceReady),
            new ToolSpec("fs_edit",
                    "{\"name\":\"fs_edit\",\"defer_loading\":true,\"description\":\"Replace an exact text snippet inside an existing workspace file\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"old_text\":{\"type\":\"string\"},\"new_text\":{\"type\":\"string\"}},\"required\":[\"path\",\"old_text\",\"new_text\"]}}",
                    Arrays.asList("path", "old_text", "new_text"),
                    "edit patch replace modify update file note workspace edytuj zmien plik notatka", true, c -> c.workspaceReady),
            new ToolSpec("fs_delete",
                    "{\"name\":\"fs_delete\",\"defer_loading\":true,\"description\":\"Delete a file from the local workspace\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}",
                    Collections.singletonList("path"),
                    "delete remove file note workspace usun plik notatka", true, c -> c.workspaceReady),
            new ToolSpec("calculator",
                    "{\"name\":\"calculator\",\"defer_loading\":true,\"description\":\"Evaluate an arithmetic expression\",\"parameters\":{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}},\"required\":[\"expression\"]}}",
                    Collections.singletonList("expression"),
                    "calculator math arithmetic compute calculate kalkulator oblicz matematyka", true, c -> true),
            new ToolSpec("unit_convert",
                    "{\"name\":\"unit_convert\",\"defer_loading\":true,\"description\":\"Convert length, mass, temperature, volume, or speed\",\"parameters\":{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"number\"},\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"}},\"required\":[\"value\",\"from\",\"to\"]}}",
                    Arrays.asList("value", "from", "to"),
                    "convert units length mass temperature speed km miles kg celsius przelicz jednostki", true, c -> true),
            new ToolSpec("weather",
                    "{\"name\":\"weather\",\"defer_loading\":true,\"description\":\"Get current weather for a city or coordinates\",\"parameters\":{\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"string\"}},\"required\":[\"location\"]}}",
                    Arrays.asList("location", "latitude", "longitude"),
                    "weather forecast temperature rain wind pogoda temperatura prognoza", true, c -> c.weatherEnabled),
            new ToolSpec("set_alarm",
                    "{\"name\":\"set_alarm\",\"defer_loading\":true,\"description\":\"Set a clock alarm\",\"parameters\":{\"type\":\"object\",\"properties\":{\"hour\":{\"type\":\"integer\"},\"minute\":{\"type\":\"integer\"},\"label\":{\"type\":\"string\"}},\"required\":[\"hour\",\"minute\"]}}",
                    Arrays.asList("hour", "minute", "label", "days"),
                    "alarm wake clock budzik alarm godzina", true, c -> true),
            new ToolSpec("set_reminder",
                    "{\"name\":\"set_reminder\",\"defer_loading\":true,\"description\":\"Schedule a local reminder notification\",\"parameters\":{\"type\":\"object\",\"properties\":{\"when\":{\"type\":\"string\",\"description\":\"Local ISO datetime YYYY-MM-DDTHH:MM\"},\"title\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}},\"required\":[\"when\",\"title\"]}}",
                    Arrays.asList("when", "title", "body", "when_epoch_sec"),
                    "reminder notify alert schedule przypomnienie przypomnij", true, c -> true),
            new ToolSpec("show_alarms",
                    "{\"name\":\"show_alarms\",\"defer_loading\":true,\"description\":\"Open the device alarms list\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}",
                    Collections.emptyList(),
                    "show alarms clock list budziki pokaz alarmy", true, c -> true),
            new ToolSpec("list_events",
                    "{\"name\":\"list_events\",\"defer_loading\":true,\"description\":\"List calendar events in a time window\",\"parameters\":{\"type\":\"object\",\"properties\":{\"window_start\":{\"type\":\"string\",\"description\":\"Local ISO datetime YYYY-MM-DDTHH:MM\"},\"window_end\":{\"type\":\"string\",\"description\":\"Local ISO datetime YYYY-MM-DDTHH:MM\"},\"limit\":{\"type\":\"integer\"}},\"required\":[\"window_start\",\"window_end\"]}}",
                    Arrays.asList("window_start", "window_end", "limit", "window_start_epoch_sec", "window_end_epoch_sec"),
                    "calendar list events schedule agenda meetings kalendarz wydarzenia plan spotkania", true, c -> true),
            new ToolSpec("update_event",
                    "{\"name\":\"update_event\",\"defer_loading\":true,\"description\":\"Update an existing calendar event by event_id\",\"parameters\":{\"type\":\"object\",\"properties\":{\"event_id\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"start\":{\"type\":\"string\"},\"end\":{\"type\":\"string\"}},\"required\":[\"event_id\",\"title\",\"start\",\"end\"]}}",
                    Arrays.asList("event_id", "title", "start", "end", "start_epoch_sec", "end_epoch_sec"),
                    "calendar update edit reschedule move event kalendarz edytuj zmien wydarzenie", true, c -> true),
            new ToolSpec("delete_event",
                    "{\"name\":\"delete_event\",\"defer_loading\":true,\"description\":\"Delete a calendar event by event_id\",\"parameters\":{\"type\":\"object\",\"properties\":{\"event_id\":{\"type\":\"string\"}},\"required\":[\"event_id\"]}}",
                    Collections.singletonList("event_id"),
                    "calendar delete remove cancel event kalendarz usun odwolaj wydarzenie", true, c -> true),
            new ToolSpec("toggle_flashlight",
                    "{\"name\":\"toggle_flashlight\",\"defer_loading\":true,\"description\":\"Turn phone flashlight on or off\",\"parameters\":{\"type\":\"object\",\"properties\":{\"on\":{\"type\":\"boolean\"}},\"required\":[\"on\"]}}",
                    Collections.singletonList("on"),
                    "flashlight torch light lamp latarka swiatlo", true, c -> true),
            new ToolSpec("set_volume",
                    "{\"name\":\"set_volume\",\"defer_loading\":true,\"description\":\"Set device audio volume percent (0-100)\",\"parameters\":{\"type\":\"object\",\"properties\":{\"stream\":{\"type\":\"string\",\"enum\":[\"music\",\"alarm\",\"ring\",\"notification\"]},\"percent\":{\"type\":\"integer\"}},\"required\":[\"stream\",\"percent\"]}}",
                    Arrays.asList("stream", "percent"),
                    "volume audio sound music ring mute glosnosc dzwiek muzyka", true, c -> true),
            new ToolSpec("open_application",
                    "{\"name\":\"open_application\",\"defer_loading\":true,\"description\":\"Launch an installed Android app by package id\",\"parameters\":{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"}},\"required\":[\"package\"]}}",
                    Collections.singletonList("package"),
                    "open launch app application package otworz uruchom aplikacje", true, c -> true),
            new ToolSpec("open_settings",
                    "{\"name\":\"open_settings\",\"defer_loading\":true,\"description\":\"Open an Android settings page\",\"parameters\":{\"type\":\"object\",\"properties\":{\"page\":{\"type\":\"string\",\"enum\":[\"wifi\",\"bluetooth\",\"sound\",\"display\",\"application\"]}},\"required\":[\"page\"]}}",
                    Collections.singletonList("page"),
                    "settings wifi bluetooth sound display screen ustawienia ekran dzwiek", true, c -> true),
            new ToolSpec("copy_text",
                    "{\"name\":\"copy_text\",\"defer_loading\":true,\"description\":\"Copy text to clipboard\",\"parameters\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}}",
                    Collections.singletonList("text"),
                    "copy clipboard paste text skopiuj schowek tekst", true, c -> true),
            new ToolSpec("share_text",
                    "{\"name\":\"share_text\",\"defer_loading\":true,\"description\":\"Share text with another app\",\"parameters\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}}",
                    Collections.singletonList("text"),
                    "share send text app udostepnij wyslij tekst", true, c -> true),
            new ToolSpec("draft_message",
                    "{\"name\":\"draft_message\",\"defer_loading\":true,\"description\":\"Create a message draft (sms or allowlisted share app)\",\"parameters\":{\"type\":\"object\",\"properties\":{\"channel\":{\"type\":\"string\",\"enum\":[\"sms\",\"share\"]},\"to\":{\"type\":\"string\"},\"package\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}},\"required\":[\"channel\",\"body\"]}}",
                    Arrays.asList("channel", "to", "package", "body"),
                    "message sms text whatsapp telegram signal draft wiadomosc napisz sms", true, c -> true),
            new ToolSpec("send_message",
                    "{\"name\":\"send_message\",\"defer_loading\":true,\"description\":\"Open composer for a previously drafted message\",\"parameters\":{\"type\":\"object\",\"properties\":{\"draft_id\":{\"type\":\"string\"}},\"required\":[\"draft_id\"]}}",
                    Collections.singletonList("draft_id"),
                    "send message sms composer draft wyslij wiadomosc", true, c -> true),
            new ToolSpec("list_notifications",
                    "{\"name\":\"list_notifications\",\"defer_loading\":true,\"description\":\"List recent notifications from allowed apps\",\"parameters\":{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"},\"package\":{\"type\":\"string\"}}}}",
                    Arrays.asList("limit", "package"),
                    "notifications alerts unread messages powiadomienia alerty", true, c -> c.notificationsEnabled),
            new ToolSpec("lookup_contact",
                    "{\"name\":\"lookup_contact\",\"defer_loading\":true,\"description\":\"Search local phone contacts by name, number, or email offline\",\"parameters\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}},\"required\":[\"query\"]}}",
                    Arrays.asList("query", "limit"),
                    "contact contacts phone number email person sms call kontakt kontakty numer telefon osoba", true, c -> true)
    ));

    private LfmToolCatalog() {}

    /** Returns always-loaded core tools enabled for the given capabilities. */
    public static List<ToolSpec> coreTools(Capabilities caps) {
        Capabilities c = caps == null ? Capabilities.offlineDefaults() : caps;
        List<ToolSpec> out = new ArrayList<>();
        for (ToolSpec spec : BUILTIN_SPECS) {
            if (!spec.deferred && spec.isEnabled(c)) out.add(spec);
        }
        for (ToolSpec extra : c.extraTools) {
            if (extra != null && !extra.deferred && extra.isEnabled(c)) out.add(extra);
        }
        return out;
    }

    /** Returns all enabled tools (core + deferred + dynamic extra tools). */
    public static List<ToolSpec> allEnabledTools(Capabilities caps) {
        Capabilities c = caps == null ? Capabilities.offlineDefaults() : caps;
        List<ToolSpec> out = new ArrayList<>();
        for (ToolSpec spec : BUILTIN_SPECS) {
            if (spec.isEnabled(c)) out.add(spec);
        }
        for (ToolSpec extra : c.extraTools) {
            if (extra != null && extra.isEnabled(c)) out.add(extra);
        }
        return out;
    }

    /**
     * Searches enabled deferred (and extra) tools by EN/PL keywords and returns an LFM3 native
     * {@code <additional_tools>} payload ready to place inside {@code <tool_response>}.
     */
    public static SearchResult searchTools(String query, Capabilities caps, int maxResults) {
        Capabilities c = caps == null ? Capabilities.offlineDefaults() : caps;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(maxResults, 10));
        String[] terms = q.isEmpty() ? new String[0] : q.split("[\\s,;/]+");

        List<ToolSpec> pool = allEnabledTools(c);
        Map<ToolSpec, Integer> scores = new LinkedHashMap<>();
        for (ToolSpec spec : pool) {
            if ("search_tools".equals(spec.name)) continue;
            if (terms.length == 0) {
                if (spec.deferred) scores.put(spec, 1);
                continue;
            }
            int score = 0;
            String hay = (spec.name + " " + spec.keywords + " " + spec.schemaJson).toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (term.isEmpty()) continue;
                if (spec.name.equalsIgnoreCase(term)) score += 10;
                else if (spec.name.toLowerCase(Locale.ROOT).contains(term)) score += 6;
                if (spec.keywords.contains(term)) score += 4;
                else if (hay.contains(term)) score += 2;
            }
            if (score > 0) {
                if (spec.deferred) score += 1;
                scores.put(spec, score);
            }
        }

        List<Map.Entry<ToolSpec, Integer>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        List<ToolSpec> matched = new ArrayList<>();
        for (int i = 0; i < ranked.size() && matched.size() < limit; i++) {
            matched.add(ranked.get(i).getKey());
        }

        if (matched.isEmpty()) {
            StringBuilder available = new StringBuilder("No tools matched \"");
            available.append(q).append("\". Available tool names: ");
            boolean first = true;
            for (ToolSpec spec : pool) {
                if ("search_tools".equals(spec.name)) continue;
                if (!first) available.append(", ");
                available.append(spec.name);
                first = false;
            }
            return new SearchResult(Collections.emptyList(), available.toString());
        }

        StringBuilder out = new StringBuilder();
        out.append("Loaded ").append(matched.size()).append(" matching tool(s). You can call them now.\n");
        out.append("<additional_tools>\n");
        for (ToolSpec spec : matched) {
            out.append(spec.schemaJson).append('\n');
        }
        out.append("</additional_tools>");
        return new SearchResult(matched, out.toString());
    }

    /**
     * Static system prompt with LFM3 native XML tool-calling instructions and compact core schemas.
     * Additional tools are loaded lazily via {@code search_tools} to keep KV prefix reuse fast.
     */
    public static String systemPrompt(Capabilities caps, Locale locale) {
        Capabilities c = caps == null ? Capabilities.offlineDefaults() : caps;
        StringBuilder out = new StringBuilder(1536);
        out.append("You are Hanami, a concise helpful on-device Android assistant. ")
                .append("Reply in the language of the user. Use attached media when present. Respond in text.\n")
                .append("For general conversation, knowledge, recipes, writing, coding, and explanations, ")
                .append("answer directly from your own knowledge without tools.\n")
                .append("Use the tools listed in <tools> below only when the user asks for a device action, ")
                .append("live lookup, calendar/notes/files, or external tool. If a needed tool is not listed yet, ")
                .append("call search_tools first. Never claim a device action succeeded before receiving a <tool_response>.\n")
                .append("To invoke a tool, output:\n")
                .append("<tool_call>\n<function=TOOL_NAME>\n<parameter=KEY>VALUE</parameter>\n</function>\n</tool_call>\n");
        if (c.webSearchEnabled) {
            out.append("Web search is ON: call web_search for current events, live facts, or anything you are unsure about, then cite sources as [1], [2].\n");
        }
        if (c.workspaceReady) {
            out.append("Local workspace is MOUNTED: you can search, read, create, and edit notes/files inside the user's folder (writes require user approval).\n");
        }
        if (locale != null && "pl".equals(locale.getLanguage())) {
            out.append("Reply in Polish when appropriate.\n");
        }
        out.append("<tools>\n");
        for (ToolSpec spec : coreTools(c)) {
            out.append(spec.schemaJson).append('\n');
        }
        out.append("</tools>");
        return out.toString();
    }

    /**
     * Lightweight EN/PL intent gate so casual offline chat (greetings, recipes, knowledge, writing,
     * explanations) uses the fast 23-token chat prompt with full ngram-mod speculative decoding,
     * while device actions, web search, workspace notes/files, calendar, timers, and external tools
     * seamlessly engage the full LFM3 tool loop.
     */
    public static boolean shouldEngageToolMode(String userText, boolean useSearch, Capabilities caps) {
        if (useSearch) return true;
        Capabilities c = caps == null ? Capabilities.offlineDefaults() : caps;
        if (c.webSearchEnabled) return true;
        if (userText == null) return false;
        String q = userText.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return false;

        // Check configured custom shortcut / MCP tool names & keywords first.
        for (ToolSpec extra : c.extraTools) {
            if (extra == null) continue;
            if (extra.name != null && !extra.name.isEmpty()
                    && q.contains(extra.name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        // Workspace / local file / note cues when a vault is mounted.
        if (c.workspaceReady) {
            if (q.contains(".md") || q.contains(".txt") || q.contains("vault")
                    || q.contains("workspace") || q.contains("obsidian")
                    || containsAnyWord(q, "note", "notes", "notatka", "notatkę", "notatki",
                    "notatek", "file", "files", "plik", "pliki", "folder", "katalog", "todo")) {
                return true;
            }
        }

        // Device actions, calendar, timers, alarms, reminders, clock, weather, contacts,
        // notifications, messaging, calculator/units, clipboard/share, and tool discovery.
        if (containsAnyWord(q,
                "timer", "countdown", "minutnik", "stoper", "odliczanie",
                "alarm", "alarms", "budzik", "budziki", "obudź",
                "reminder", "remind", "przypomnienie", "przypomnij",
                "calendar", "kalendarz", "schedule", "appointment", "meeting",
                "wydarzenie", "spotkanie", "zaplanuj", "agenda", "event", "events",
                "flashlight", "torch", "latarka", "latarkę",
                "volume", "mute", "głośność", "wycisz", "ścisz", "podgłośnij",
                "settings", "ustawienia", "wifi", "bluetooth",
                "weather", "forecast", "pogoda", "prognoza",
                "notification", "notifications", "powiadomienie", "powiadomienia",
                "contact", "contacts", "kontakt", "kontakty",
                "sms", "whatsapp", "telegram", "signal", "wiadomość", "wyślij",
                "clipboard", "schowek", "skopiuj", "udostępnij",
                "calculator", "calculate", "kalkulator", "oblicz", "policz", "przelicz",
                "convert", "celsius", "fahrenheit", "kilometers", "miles",
                "tool", "tools", "narzędzia", "narzędzie", "mcp", "shortcut", "skrót")) {
            return true;
        }

        // Multi-word EN/PL action phrases (avoid triggering on casual words like "time", "open", "search").
        String[] phrases = {
                "what time", "current time", "what date", "what day is", "today's date",
                "która godzina", "jaka godzina", "jaki dziś dzień", "jaka dziś data",
                "open app", "launch app", "otwórz aplikacj", "uruchom aplikacj",
                "send a message", "draft a message", "text message", "napisz wiadomość",
                "copy to clipboard", "share this", "turn on the light", "turn off the light",
                "włącz latark", "wyłącz latark", "ustaw minutnik", "ustaw budzik",
                "dodaj do kalendarz", "sprawdź kalendarz", "szukaj w sieci", "search the web",
                "search my notes", "read my ", "list my files", "create a note", "save a note"
        };
        for (String phrase : phrases) {
            if (q.contains(phrase)) return true;
        }
        return false;
    }

    private static boolean containsAnyWord(String text, String... words) {
        int len = text.length();
        for (String word : words) {
            int idx = text.indexOf(word);
            while (idx >= 0) {
                boolean startOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
                int end = idx + word.length();
                boolean endOk = end == len || !Character.isLetterOrDigit(text.charAt(end));
                if (startOk && endOk) return true;
                idx = text.indexOf(word, idx + 1);
            }
        }
        return false;
    }

    /**
     * One-pass voice-command system prompt: voice commands execute in a single inference turn
     * without a multi-step {@code search_tools} round trip, so enabled device/calendar tools are
     * listed directly inside {@code <tools>}.
     */
    public static String voiceOnePassSystemPrompt(Capabilities caps, Locale locale) {
        Capabilities c = caps == null ? Capabilities.offlineDefaults() : caps;
        StringBuilder out = new StringBuilder(2048);
        out.append("You are Hanami, a concise helpful on-device Android voice assistant. ")
                .append("Reply in the language of the user. ")
                .append("When a tool fulfills the spoken request, output:\n")
                .append("<tool_call>\n<function=TOOL_NAME>\n<parameter=KEY>VALUE</parameter>\n</function>\n</tool_call>\n");
        if (locale != null && "pl".equals(locale.getLanguage())) {
            out.append("Reply in Polish when appropriate.\n");
        }
        out.append("<tools>\n");
        for (ToolSpec spec : allEnabledTools(c)) {
            if ("search_tools".equals(spec.name)) continue;
            out.append(spec.schemaJson).append('\n');
        }
        out.append("</tools>");
        return out.toString();
    }

    /**
     * Compact per-turn grounding context used only when tool mode is active.
     * Omits {@code web_search=off} so small models do not treat offline chat as a refusal cue.
     */
    public static String turnContextLine(ZonedDateTime now, Capabilities caps) {
        ZonedDateTime clock = now == null ? ZonedDateTime.now() : now;
        Capabilities c = caps == null ? Capabilities.offlineDefaults() : caps;
        StringBuilder sb = new StringBuilder("[Context: local time=");
        sb.append(clock.format(CONTEXT_TIME));
        if (c.calendarWriteReady) {
            sb.append(" | calendar=");
            sb.append(c.pickedCalendarLabel.isEmpty() ? "ready" : sanitizeInline(c.pickedCalendarLabel));
        } else if (c.calendarReadReady) {
            sb.append(" | calendar=read-only (pick a calendar in Settings to write)");
        }
        if (c.workspaceReady) {
            sb.append(" | workspace=");
            sb.append(c.workspaceLabel.isEmpty() ? "mounted" : sanitizeInline(c.workspaceLabel));
        }
        if (c.webSearchEnabled) {
            sb.append(" | web_search=on");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String sanitizeInline(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').replace('[', '(').replace(']', ')');
    }
}
