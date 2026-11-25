package com.notcherry.lfm3pocket;

import static org.junit.Assert.*;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.Executor;

/** Unit tests for LFM3 native tool calling, multi-turn loop, web_search tool, and calendar fixes. */
public final class LfmAgentHarnessTest {
    private static final class Queue implements Executor, ChatSession.Dispatcher {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public void post(Runnable task, long delay) { tasks.add(task); }
        void drain() { while (!tasks.isEmpty()) tasks.remove(0).run(); }
    }

    private static final class ScriptedEngine implements InferenceEngine {
        final Deque<String> replies = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        @Override public long loadModel(String m, String p) { return 1L; }
        @Override public void unloadModel(long h) {}
        @Override public String generate(long h, String p, String media, int max, TokenSink sink) {
            return generateWithMedia(h, p, media == null ? new String[0] : new String[]{media}, max, sink);
        }
        @Override public String generateWithMedia(long h, String p, String[] media, int max, TokenSink sink) {
            prompts.add(p);
            String next = replies.isEmpty() ? "Fallback reply" : replies.removeFirst();
            sink.onToken(next);
            return null;
        }
        @Override public void stop() {}
        @Override public void resetStop() {}
    }

    @Test public void catalogIncludesWebSearchAndWeatherOnlyWhenEnabled() {
        LfmToolCatalog.Capabilities offline = LfmToolCatalog.Capabilities.offlineDefaults();
        String offPrompt = LfmToolCatalog.systemPrompt(offline, Locale.US);
        assertTrue(offPrompt.contains("<tools>"));
        assertTrue(offPrompt.contains("\"name\":\"create_event\""));
        assertTrue(offPrompt.contains("\"name\":\"set_timer\""));
        assertFalse(offPrompt.contains("\"name\":\"web_search\""));
        assertFalse(offPrompt.contains("\"name\":\"weather\""));

        LfmToolCatalog.Capabilities online = new LfmToolCatalog.Capabilities(
                true, true, true, true, "Personal", true);
        String onPrompt = LfmToolCatalog.systemPrompt(online, new Locale("pl"));
        assertTrue(onPrompt.contains("\"name\":\"web_search\""));
        assertTrue(onPrompt.contains("\"name\":\"weather\""));
        assertTrue(onPrompt.contains("\"name\":\"list_notifications\""));
        assertTrue(onPrompt.contains("Polish"));

        String ctx = LfmToolCatalog.turnContextLine(
                ZonedDateTime.of(2026, 11, 25, 11, 42, 0, 0, ZoneId.of("UTC")), online);
        assertTrue(ctx.contains("2026-11-25"));
        assertTrue(ctx.contains("calendar=Personal"));
        assertTrue(ctx.contains("web_search=on"));
    }

    @Test public void bridgeParsesLfm3XmlAndLfm2PythonicAndStripsMarkup() {
        ToolRegistry registry = new ToolRegistry();
        String xml = "Let me schedule that.\n<tool_call>\n<function=create_event>\n"
                + "<parameter=title>Dentist</parameter>\n"
                + "<parameter=start>2026-11-26T15:00</parameter>\n"
                + "</function>\n</tool_call>";
        ToolRegistry.Parsed parsedXml = ModelToolBridge.parseFromAssistant(registry, xml);
        assertNotNull(parsedXml);
        assertTrue(parsedXml.status + ": " + parsedXml.message, parsedXml.isValid());
        ToolRegistry.CreateEventRequest ev = (ToolRegistry.CreateEventRequest) parsedXml.request;
        assertEquals("Dentist", ev.title);
        assertEquals("", ev.calendarId);
        assertFalse(ev.allDay);
        assertEquals(3600L, ev.endEpochSec - ev.startEpochSec);
        assertEquals("Let me schedule that.", ModelToolBridge.stripToolCallMarkup(xml));

        String lfm2 = "<|tool_call_start|>[set_timer(seconds=90, label=\"Coffee\")]<|tool_call_end|>";
        ToolRegistry.Parsed parsedLfm2 = ModelToolBridge.parseFromAssistant(registry, lfm2);
        assertNotNull(parsedLfm2);
        assertTrue(parsedLfm2.isValid());
        ToolRegistry.TimerRequest timer = (ToolRegistry.TimerRequest) parsedLfm2.request;
        assertEquals(90, timer.seconds);
        assertEquals("Coffee", timer.label);
        assertEquals("", ModelToolBridge.stripToolCallMarkup(lfm2));
    }

    @Test public void dateOnlyCreateEventDefaultsToAllDayAndSnapsUtcMidnight() {
        ToolRegistry registry = new ToolRegistry();
        ToolRegistry.Parsed parsed = registry.parse(
                "{\"tool\":\"create_event\",\"arguments\":{\"title\":\"Holiday\",\"start\":\"2026-12-25\"}}");
        assertTrue(parsed.status + ": " + parsed.message, parsed.isValid());
        ToolRegistry.CreateEventRequest req = (ToolRegistry.CreateEventRequest) parsed.request;
        assertTrue(req.allDay);
        long[] snapped = AndroidCalendarGateway.snapAllDayUtcMidnight(req.startEpochSec, req.endEpochSec);
        assertEquals(0L, snapped[0] % 86400L);
        assertEquals(0L, snapped[1] % 86400L);
        assertTrue(snapped[1] - snapped[0] >= 86400L);
    }

    @Test public void calendarDisplayFormatIsHumanFriendlyAndStripsUntrustedMarker() {
        ToolRegistry registry = new ToolRegistry();
        ToolRegistry.Request req = registry.parse(
                "{\"tool\":\"create_event\",\"arguments\":{\"title\":\"Dentist\",\"start\":\"2026-11-26T15:00\"}}").request;
        String display = ToolCoordinator.formatForDisplay(req, "Personal");
        assertTrue(display.contains("Create calendar event"));
        assertTrue(display.contains("Title: Dentist"));
        assertTrue(display.contains("Calendar: Personal"));
        assertFalse("Should not show raw epoch keys", display.contains("start_epoch_sec"));
        assertFalse("Should not show empty optional fields", display.contains("Location:"));
        assertEquals("created event_id=42 title=\"Dentist\"",
                ToolCoordinator.stripUntrustedPrefix("UNTRUSTED_CALENDAR created event_id=42 title=\"Dentist\""));
    }

    @Test public void webSearchNormalizesEndpointAndExecutesWhenEnabled() {
        assertEquals("https://searx.example.org/search", WebSearch.normalizeEndpoint("https://searx.example.org"));
        assertEquals("https://searx.example.org/search", WebSearch.normalizeEndpoint("https://searx.example.org/"));
        assertEquals("https://searx.example.org/search", WebSearch.normalizeEndpoint("https://searx.example.org/search"));
        assertEquals("https://searx.example.org/api/search", WebSearch.normalizeEndpoint("https://searx.example.org/api/search"));

        BuiltinToolsExecutor off = new BuiltinToolsExecutor(() -> false, url -> "", () -> false,
                () -> "https://searx.example.org/search", (ep, q, r) -> Collections.emptyList());
        assertEquals("WEB_SEARCH_DISABLED", off.execute("web_search", Collections.singletonMap("query", "news")).status);

        BuiltinToolsExecutor on = new BuiltinToolsExecutor(() -> false, url -> "", () -> true,
                () -> "https://searx.example.org", (ep, q, r) -> Collections.singletonList(
                        new WebSearch.Source("Example News", "Headline snippet", "https://news.example/1")));
        AndroidActionExecutor.ActionResult res = on.execute("web_search", Collections.singletonMap("query", "news"));
        assertTrue(res.succeeded);
        assertTrue(res.message.startsWith("UNTRUSTED_WEB_SEARCH"));
        assertTrue(res.message.contains("Example News"));
    }

    @Test public void legacySearchExceptionIsSurfacedInCompletionStatus() {
        Queue worker = new Queue(), ui = new Queue();
        ScriptedEngine engine = new ScriptedEngine();
        engine.replies.add("Offline fallback answer.");
        String[] status = {""};
        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (endpoint, q) -> { throw new Exception("SearXNG returned HTTP 403 (ensure JSON format is enabled)"); },
                (text, s, running) -> status[0] = s);
        assertTrue(session.submit("latest news", "", "", true, "https://searx.example/search", 64));
        worker.drain(); ui.drain();
        assertTrue("Search error must be visible in status: " + status[0],
                status[0].contains("HTTP 403"));
    }

    @Test public void agentLoopAutoExecutesWebSearchAndAttachesClickableSources() {
        Queue worker = new Queue(), ui = new Queue();
        ScriptedEngine engine = new ScriptedEngine();
        engine.replies.add("<tool_call>\n<function=web_search>\n<parameter=query>Liquid AI LFM3</parameter>\n</function>\n</tool_call>");
        engine.replies.add("LFM3 is Liquid AI's latest edge model family [1].");
        List<String> searchedQueries = new ArrayList<>();
        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (endpoint, q) -> {
                    searchedQueries.add(q);
                    return Collections.singletonList(new WebSearch.Source(
                            "Liquid AI LFM3", "New on-device backbone", "https://liquid.ai/lfm3"));
                },
                (text, s, running) -> {});
        session.setToolHost(new ChatSession.ToolHost() {
            @Override public LfmToolCatalog.Capabilities capabilities(boolean useSearch, String ep) {
                return new LfmToolCatalog.Capabilities(useSearch, false, false, false, "", false);
            }
            @Override public Locale locale() { return Locale.US; }
            @Override public ToolRegistry registry() { return new ToolRegistry(); }
            @Override public boolean canAutoExecute(ToolRegistry.Request req) {
                return req.tool == ToolRegistry.Tool.WEB_SEARCH;
            }
            @Override public AndroidActionExecutor.ActionResult executeReadOnly(ToolRegistry.Request req) {
                return new AndroidActionExecutor.ActionResult(false, "UNUSED", "");
            }
            @Override public void proposeTool(ToolRegistry.Request req) {
                fail("web_search should auto-execute");
            }
        });

        assertTrue(session.submit("Tell me about LFM3", "", "", true, "https://searx.example/search", 128));
        worker.drain(); ui.drain();

        assertEquals(2, engine.prompts.size());
        assertTrue(engine.prompts.get(0).contains("\"name\":\"web_search\""));
        assertTrue(engine.prompts.get(1).contains("<tool_response>"));
        assertTrue(engine.prompts.get(1).contains("New on-device backbone"));
        assertEquals(Collections.singletonList("Liquid AI LFM3"), searchedQueries);
        ChatSnapshot.Turn reply = session.snapshot().turns.get(1);
        assertEquals("LFM3 is Liquid AI's latest edge model family [1].", reply.text);
        assertEquals(1, reply.sources.size());
        assertEquals("https://liquid.ai/lfm3", reply.sources.get(0).url);
    }

    @Test public void agentLoopSelfCorrectsInvalidCallAndProposesCalendarEventCleanly() {
        Queue worker = new Queue(), ui = new Queue();
        ScriptedEngine engine = new ScriptedEngine();
        // First attempt misses required title; second fixes it.
        engine.replies.add("<tool_call><function=create_event><parameter=title></parameter><parameter=start>2026-11-26T15:00</parameter></function></tool_call>");
        engine.replies.add("<tool_call><function=create_event><parameter=title>Dentist</parameter><parameter=start>2026-11-26T15:00</parameter></function></tool_call>");
        List<ToolRegistry.Request> proposed = new ArrayList<>();
        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (endpoint, q) -> Collections.emptyList(), (text, s, running) -> {});
        session.setToolHost(new ChatSession.ToolHost() {
            @Override public LfmToolCatalog.Capabilities capabilities(boolean useSearch, String ep) {
                return new LfmToolCatalog.Capabilities(false, false, true, true, "Personal", false);
            }
            @Override public Locale locale() { return Locale.US; }
            @Override public ToolRegistry registry() { return new ToolRegistry(); }
            @Override public boolean canAutoExecute(ToolRegistry.Request req) { return false; }
            @Override public AndroidActionExecutor.ActionResult executeReadOnly(ToolRegistry.Request req) {
                return new AndroidActionExecutor.ActionResult(false, "UNUSED", "");
            }
            @Override public void proposeTool(ToolRegistry.Request req) { proposed.add(req); }
        });

        assertTrue(session.submit("Schedule Dentist tomorrow at 15:00", "", "", false, "", 128));
        worker.drain(); ui.drain();

        assertEquals(2, engine.prompts.size());
        assertTrue("Retry prompt must include validation error", engine.prompts.get(1).contains("Error:"));
        assertEquals(1, proposed.size());
        assertEquals(ToolRegistry.Tool.CREATE_EVENT, proposed.get(0).tool);
        String visible = session.snapshot().turns.get(1).text;
        assertFalse("Visible chat bubble must not contain raw XML", visible.contains("<tool_call>"));
        assertTrue(visible.contains("Create calendar event"));
    }
}
