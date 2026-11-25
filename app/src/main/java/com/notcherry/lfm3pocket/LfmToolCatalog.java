package com.notcherry.lfm3pocket;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds Liquid AI LFM3 native {@code <tools>...</tools>} system prompts and compact per-turn
 * grounding context (local clock, timezone, enabled capabilities).
 *
 * <p>Keeping the tool catalog static in the system prompt preserves {@code native.cpp} KV-cache
 * prefix reuse across turns; volatile clock metadata is injected only into the current user turn.
 */
public final class LfmToolCatalog {
    public static final class Capabilities {
        public final boolean webSearchEnabled;
        public final boolean weatherEnabled;
        public final boolean calendarReadReady;
        public final boolean calendarWriteReady;
        public final String pickedCalendarLabel;
        public final boolean notificationsEnabled;

        public Capabilities(boolean webSearchEnabled, boolean weatherEnabled,
                            boolean calendarReadReady, boolean calendarWriteReady,
                            String pickedCalendarLabel, boolean notificationsEnabled) {
            this.webSearchEnabled = webSearchEnabled;
            this.weatherEnabled = weatherEnabled;
            this.calendarReadReady = calendarReadReady;
            this.calendarWriteReady = calendarWriteReady;
            this.pickedCalendarLabel = pickedCalendarLabel == null ? "" : pickedCalendarLabel.trim();
            this.notificationsEnabled = notificationsEnabled;
        }

        public static Capabilities offlineDefaults() {
            return new Capabilities(false, false, false, false, "", false);
        }
    }

    private static final DateTimeFormatter CONTEXT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd (EEE) HH:mm z", Locale.ROOT);

    private LfmToolCatalog() {}

    /**
     * Static system prompt with LFM3 native XML tool-calling instructions and compact JSON schemas.
     * Only enabled optional capabilities (e.g. {@code web_search}, {@code weather}) are advertised.
     */
    public static String systemPrompt(Capabilities caps, Locale locale) {
        Capabilities c = caps == null ? Capabilities.offlineDefaults() : caps;
        StringBuilder out = new StringBuilder(2048);
        out.append("You are Hanami, a concise helpful on-device Android assistant. ")
                .append("Reply in the language of the user. Use attached media when present. Respond in text.\n")
                .append("You can use the tools listed in <tools> below to perform real actions or look up information. ")
                .append("When a tool helps answer or fulfill the request, call it instead of claiming you cannot. ")
                .append("Never claim an action succeeded before receiving a <tool_response>.\n")
                .append("To invoke a tool, output:\n")
                .append("<tool_call>\n<function=TOOL_NAME>\n<parameter=KEY>VALUE</parameter>\n</function>\n</tool_call>\n");
        if (c.webSearchEnabled) {
            out.append("Web search is ON: call web_search for current events, live facts, or anything you are unsure about, then cite sources as [1], [2].\n");
        }
        if (locale != null && "pl".equals(locale.getLanguage())) {
            out.append("Reply in Polish when appropriate.\n");
        }
        out.append("<tools>\n");
        if (c.webSearchEnabled) {
            out.append("{\"name\":\"web_search\",\"description\":\"Search the web for current information\",\"parameters\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Concise search query\"},\"time_range\":{\"type\":\"string\",\"enum\":[\"day\",\"week\",\"month\",\"year\"]}},\"required\":[\"query\"]}}\n");
        }
        out.append("{\"name\":\"current_time\",\"description\":\"Get current local date and time\",\"parameters\":{\"type\":\"object\",\"properties\":{\"timezone\":{\"type\":\"string\",\"description\":\"Optional IANA timezone\"}}}}\n");
        out.append("{\"name\":\"calculator\",\"description\":\"Evaluate an arithmetic expression\",\"parameters\":{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}},\"required\":[\"expression\"]}}\n");
        out.append("{\"name\":\"unit_convert\",\"description\":\"Convert length, mass, temperature, volume, or speed\",\"parameters\":{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"number\"},\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"}},\"required\":[\"value\",\"from\",\"to\"]}}\n");
        if (c.weatherEnabled) {
            out.append("{\"name\":\"weather\",\"description\":\"Get current weather for a city or coordinates\",\"parameters\":{\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"string\"}},\"required\":[\"location\"]}}\n");
        }
        out.append("{\"name\":\"set_timer\",\"description\":\"Start a countdown timer\",\"parameters\":{\"type\":\"object\",\"properties\":{\"seconds\":{\"type\":\"integer\"},\"label\":{\"type\":\"string\"}},\"required\":[\"seconds\"]}}\n");
        out.append("{\"name\":\"set_alarm\",\"description\":\"Set a clock alarm\",\"parameters\":{\"type\":\"object\",\"properties\":{\"hour\":{\"type\":\"integer\"},\"minute\":{\"type\":\"integer\"},\"label\":{\"type\":\"string\"}},\"required\":[\"hour\",\"minute\"]}}\n");
        out.append("{\"name\":\"set_reminder\",\"description\":\"Schedule a local reminder notification\",\"parameters\":{\"type\":\"object\",\"properties\":{\"when\":{\"type\":\"string\",\"description\":\"Local ISO datetime YYYY-MM-DDTHH:MM\"},\"title\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}},\"required\":[\"when\",\"title\"]}}\n");
        out.append("{\"name\":\"show_alarms\",\"description\":\"Open the device alarms list\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}\n");
        out.append("{\"name\":\"list_events\",\"description\":\"List calendar events in a time window\",\"parameters\":{\"type\":\"object\",\"properties\":{\"window_start\":{\"type\":\"string\",\"description\":\"Local ISO datetime YYYY-MM-DDTHH:MM\"},\"window_end\":{\"type\":\"string\",\"description\":\"Local ISO datetime YYYY-MM-DDTHH:MM\"},\"limit\":{\"type\":\"integer\"}},\"required\":[\"window_start\",\"window_end\"]}}\n");
        out.append("{\"name\":\"create_event\",\"description\":\"Create a calendar event in the user's picked calendar\",\"parameters\":{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"start\":{\"type\":\"string\",\"description\":\"Local ISO datetime YYYY-MM-DDTHH:MM or YYYY-MM-DD\"},\"end\":{\"type\":\"string\",\"description\":\"Optional end YYYY-MM-DDTHH:MM (defaults to +1h)\"},\"all_day\":{\"type\":\"boolean\"},\"location\":{\"type\":\"string\"},\"notes\":{\"type\":\"string\"}},\"required\":[\"title\",\"start\"]}}\n");
        out.append("{\"name\":\"update_event\",\"description\":\"Update an existing calendar event by event_id\",\"parameters\":{\"type\":\"object\",\"properties\":{\"event_id\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"start\":{\"type\":\"string\"},\"end\":{\"type\":\"string\"}},\"required\":[\"event_id\",\"title\",\"start\",\"end\"]}}\n");
        out.append("{\"name\":\"delete_event\",\"description\":\"Delete a calendar event by event_id\",\"parameters\":{\"type\":\"object\",\"properties\":{\"event_id\":{\"type\":\"string\"}},\"required\":[\"event_id\"]}}\n");
        out.append("{\"name\":\"toggle_flashlight\",\"description\":\"Turn phone flashlight on or off\",\"parameters\":{\"type\":\"object\",\"properties\":{\"on\":{\"type\":\"boolean\"}},\"required\":[\"on\"]}}\n");
        out.append("{\"name\":\"set_volume\",\"description\":\"Set device audio volume percent (0-100)\",\"parameters\":{\"type\":\"object\",\"properties\":{\"stream\":{\"type\":\"string\",\"enum\":[\"music\",\"alarm\",\"ring\",\"notification\"]},\"percent\":{\"type\":\"integer\"}},\"required\":[\"stream\",\"percent\"]}}\n");
        out.append("{\"name\":\"open_application\",\"description\":\"Launch an installed Android app by package id\",\"parameters\":{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"}},\"required\":[\"package\"]}}\n");
        out.append("{\"name\":\"open_settings\",\"description\":\"Open an Android settings page\",\"parameters\":{\"type\":\"object\",\"properties\":{\"page\":{\"type\":\"string\",\"enum\":[\"wifi\",\"bluetooth\",\"sound\",\"display\",\"application\"]}},\"required\":[\"page\"]}}\n");
        out.append("{\"name\":\"copy_text\",\"description\":\"Copy text to clipboard\",\"parameters\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}}\n");
        out.append("{\"name\":\"share_text\",\"description\":\"Share text with another app\",\"parameters\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}}\n");
        out.append("{\"name\":\"draft_message\",\"description\":\"Create a message draft (sms or allowlisted share app)\",\"parameters\":{\"type\":\"object\",\"properties\":{\"channel\":{\"type\":\"string\",\"enum\":[\"sms\",\"share\"]},\"to\":{\"type\":\"string\"},\"package\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}},\"required\":[\"channel\",\"body\"]}}\n");
        out.append("{\"name\":\"send_message\",\"description\":\"Open composer for a previously drafted message\",\"parameters\":{\"type\":\"object\",\"properties\":{\"draft_id\":{\"type\":\"string\"}},\"required\":[\"draft_id\"]}}\n");
        if (c.notificationsEnabled) {
            out.append("{\"name\":\"list_notifications\",\"description\":\"List recent notifications from allowed apps\",\"parameters\":{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"},\"package\":{\"type\":\"string\"}}}}\n");
        }
        out.append("</tools>");
        return out.toString();
    }

    /**
     * Compact volatile context prepended only to the active user turn (so earlier KV prefix stays hot).
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
        sb.append(" | web_search=").append(c.webSearchEnabled ? "on" : "off");
        sb.append("]");
        return sb.toString();
    }

    private static String sanitizeInline(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').replace('[', '(').replace(']', ')');
    }
}
