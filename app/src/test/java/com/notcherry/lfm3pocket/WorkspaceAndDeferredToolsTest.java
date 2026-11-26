package com.notcherry.lfm3pocket;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.Executor;

/** Unit tests for Sprint 2: Local Workspace tools, deferred tool search, and taint-aware confirmation. */
public final class WorkspaceAndDeferredToolsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final class Queue implements Executor, ChatSession.Dispatcher {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public void post(Runnable task, long delay) { tasks.add(task); }
        void drain() { while (!tasks.isEmpty()) tasks.remove(0).run(); }
    }

    private static final class GrammarCapturingEngine implements InferenceEngine {
        final Deque<String> replies = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        final List<String> grammars = new ArrayList<>();
        final List<String> triggers = new ArrayList<>();
        @Override public long loadModel(String m, String p) { return 1L; }
        @Override public void unloadModel(long h) {}
        @Override public String generate(long h, String p, String media, int max, TokenSink sink) {
            return generateWithMediaAndGrammar(h, p, new String[0], max, null, null, sink);
        }
        @Override public String generateWithMediaAndGrammar(long h, String p, String[] media, int max,
                                                            String grammar, String trigger, TokenSink sink) {
            prompts.add(p);
            grammars.add(grammar == null ? "" : grammar);
            triggers.add(trigger == null ? "" : trigger);
            sink.onToken(replies.isEmpty() ? "Done." : replies.removeFirst());
            return null;
        }
        @Override public void stop() {}
        @Override public void resetStop() {}
    }

    /** Simple local-file implementation of WorkspaceGateway for JVM unit tests. */
    private static final class LocalFileWorkspaceGateway implements WorkspaceGateway {
        private final File root;
        LocalFileWorkspaceGateway(File root) { this.root = root; }

        private File resolve(String relativePath, boolean allowRoot) throws IOException {
            List<String> segs = WorkspacePath.parseSegments(relativePath, allowRoot);
            File cur = root;
            for (String s : segs) cur = new File(cur, s);
            String canonRoot = root.getCanonicalPath();
            String canonTarget = cur.getCanonicalPath();
            if (!canonTarget.equals(canonRoot) && !canonTarget.startsWith(canonRoot + File.separator)) {
                throw new IOException("Path escapes workspace");
            }
            return cur;
        }

        @Override public List<Entry> list(String relativePath, int maxDepth, int maxEntries) throws IOException {
            File dir = resolve(relativePath, true);
            if (!dir.isDirectory()) throw new IOException("Folder not found: " + relativePath);
            List<Entry> out = new ArrayList<>();
            walkList(dir, WorkspacePath.normalize(relativePath, true), 1, maxDepth, maxEntries, out);
            return out;
        }

        private void walkList(File dir, String prefix, int depth, int maxDepth, int maxEntries, List<Entry> out) {
            File[] kids = dir.listFiles();
            if (kids == null) return;
            Arrays.sort(kids, Comparator.comparing(File::getName));
            for (File f : kids) {
                if (out.size() >= maxEntries) return;
                String rel = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
                out.add(new Entry(rel, f.isDirectory(), f.isDirectory() ? 0L : f.length()));
                if (f.isDirectory() && depth < maxDepth) walkList(f, rel, depth + 1, maxDepth, maxEntries, out);
            }
        }

        @Override public List<SearchMatch> search(String query, String relativePath, int maxMatches) throws IOException {
            File dir = resolve(relativePath, true);
            List<SearchMatch> out = new ArrayList<>();
            walkSearch(dir, WorkspacePath.normalize(relativePath, true), query.toLowerCase(Locale.ROOT), maxMatches, out);
            return out;
        }

        private void walkSearch(File dir, String prefix, String needle, int max, List<SearchMatch> out) throws IOException {
            File[] kids = dir.listFiles();
            if (kids == null) return;
            Arrays.sort(kids, Comparator.comparing(File::getName));
            for (File f : kids) {
                if (out.size() >= max) return;
                String rel = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
                if (f.isDirectory()) { walkSearch(f, rel, needle, max, out); continue; }
                if (rel.toLowerCase(Locale.ROOT).contains(needle)) out.add(new SearchMatch(rel, 0, "[filename match]"));
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size() && out.size() < max; i++) {
                    if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                        out.add(new SearchMatch(rel, i + 1, lines.get(i).trim()));
                    }
                }
            }
        }

        @Override public ReadResult read(String relativePath, int startLine, int maxLines) throws IOException {
            File f = resolve(relativePath, false);
            if (!f.isFile()) throw new IOException("File not found");
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return AndroidWorkspaceGateway.formatReadResult(WorkspacePath.normalize(relativePath, false), text, startLine, maxLines);
        }

        @Override public String createFile(String relativePath, String content) throws IOException {
            File f = resolve(relativePath, false);
            if (f.exists()) throw new IOException("File already exists");
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed");
            Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
            return WorkspacePath.normalize(relativePath, false);
        }

        @Override public String appendFile(String relativePath, String content) throws IOException {
            File f = resolve(relativePath, false);
            if (!f.isFile()) throw new IOException("File not found");
            String cur = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (!cur.isEmpty() && !cur.endsWith("\n")) cur += "\n";
            cur += content + (content.endsWith("\n") ? "" : "\n");
            Files.write(f.toPath(), cur.getBytes(StandardCharsets.UTF_8));
            return WorkspacePath.normalize(relativePath, false);
        }

        @Override public EditResult editFile(String relativePath, String oldText, String newText) throws IOException {
            File f = resolve(relativePath, false);
            if (!f.isFile()) throw new IOException("File not found");
            String cur = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            int first = cur.indexOf(oldText);
            if (first < 0) throw new IOException("old_text not found");
            if (cur.indexOf(oldText, first + oldText.length()) >= 0) throw new IOException("ambiguous old_text");
            String updated = cur.substring(0, first) + newText + cur.substring(first + oldText.length());
            Files.write(f.toPath(), updated.getBytes(StandardCharsets.UTF_8));
            return new EditResult(WorkspacePath.normalize(relativePath, false), 1);
        }

        @Override public String deleteFile(String relativePath) throws IOException {
            File f = resolve(relativePath, false);
            if (!f.isFile() || !f.delete()) throw new IOException("Delete failed");
            return "Deleted " + WorkspacePath.normalize(relativePath, false);
        }
    }

    @Test public void workspacePathRejectsTraversalAndFormatsNotes() {
        assertEquals("projects/notes.md", WorkspacePath.normalize("projects//notes.md", false));
        assertEquals("", WorkspacePath.normalize("/", true));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePath.normalize("../secret.txt", false));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePath.normalize("/etc/passwd", false));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePath.normalize("a\\b.md", false));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePath.normalize("c:evil.md", false));
        assertEquals("Meeting-Notes.md", WorkspacePath.noteFilename("Meeting Notes!"));
        String md = WorkspacePath.formatMarkdownNote("Sprint Plan", "- Ship offline vault",
                Arrays.asList("work", "hanami"), ZonedDateTime.of(2026, 11, 25, 10, 0, 0, 0, ZoneOffset.UTC));
        assertTrue(md.contains("title: \"Sprint Plan\""));
        assertTrue(md.contains("tags: [\"work\", \"hanami\"]"));
        assertTrue(md.contains("# Sprint Plan"));
        assertTrue(md.contains("- Ship offline vault"));
    }

    @Test public void workspaceExecutorSupportsCreateListSearchReadAppendEditDelete() throws Exception {
        File vault = tmp.newFolder("vault");
        LocalFileWorkspaceGateway gw = new LocalFileWorkspaceGateway(vault);
        WorkspaceActionExecutor exec = new WorkspaceActionExecutor(() -> true, () -> gw);

        Map<String, String> createArgs = new LinkedHashMap<>();
        createArgs.put("title", "Grocery List");
        createArgs.put("folder", "daily");
        createArgs.put("tags", "home, shopping");
        createArgs.put("content", "- Milk\n- Coffee");
        AndroidActionExecutor.ActionResult created = exec.execute("note_create", createArgs);
        assertTrue(created.message, created.succeeded);
        assertTrue(created.message.contains("daily/Grocery-List.md"));

        // Creating same note twice fails instead of overwriting
        assertFalse(exec.execute("note_create", createArgs).succeeded);

        AndroidActionExecutor.ActionResult listed = exec.execute("fs_list", Collections.singletonMap("max_depth", "3"));
        assertTrue(listed.succeeded);
        assertTrue(listed.message.contains("daily/Grocery-List.md"));

        AndroidActionExecutor.ActionResult searched = exec.execute("fs_search", Collections.singletonMap("query", "Coffee"));
        assertTrue(searched.succeeded);
        assertTrue(searched.message.contains("daily/Grocery-List.md"));

        Map<String, String> appendArgs = new LinkedHashMap<>();
        appendArgs.put("path", "daily/Grocery-List.md");
        appendArgs.put("content", "- Apples");
        assertTrue(exec.execute("fs_append", appendArgs).succeeded);

        Map<String, String> editArgs = new LinkedHashMap<>();
        editArgs.put("path", "daily/Grocery-List.md");
        editArgs.put("old_text", "- Milk");
        editArgs.put("new_text", "- Oat Milk");
        assertTrue(exec.execute("fs_edit", editArgs).succeeded);

        AndroidActionExecutor.ActionResult read = exec.execute("fs_read", Collections.singletonMap("path", "daily/Grocery-List.md"));
        assertTrue(read.succeeded);
        assertTrue(read.message.contains("- Oat Milk"));
        assertTrue(read.message.contains("- Apples"));

        assertTrue(exec.execute("fs_delete", Collections.singletonMap("path", "daily/Grocery-List.md")).succeeded);
        assertFalse(new File(vault, "daily/Grocery-List.md").exists());
    }

    @Test public void editAndNoteCardsShowDiffAndPreview() {
        ToolRegistry registry = new ToolRegistry();
        ToolRegistry.Request editReq = registry.parse(
                "{\"tool\":\"fs_edit\",\"arguments\":{\"path\":\"notes.md\",\"old_text\":\"old\",\"new_text\":\"new\"}}").request;
        String display = ToolCoordinator.formatForDisplay(editReq, "", true);
        assertTrue(display.contains("File: notes.md"));
        assertTrue(display.contains("- old"));
        assertTrue(display.contains("+ new"));
        assertTrue(display.contains("attached/external content"));
    }

    @Test public void deferredToolSearchExpandsActiveGrammarAndExecutesReadOnlyTool() {
        Queue worker = new Queue(), ui = new Queue();
        GrammarCapturingEngine engine = new GrammarCapturingEngine();
        // Step 1: discover deferred calculator via search_tools
        engine.replies.add("<tool_call><function=search_tools><parameter=query>calculator math</parameter></function></tool_call>");
        // Step 2: call newly discovered calculator
        engine.replies.add("<tool_call><function=calculator><parameter=expression>6 * 7</parameter></function></tool_call>");
        // Step 3: final answer
        engine.replies.add("6 * 7 equals 42.");

        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (ep, q) -> Collections.emptyList(), (t, s, g) -> {});
        session.setToolHost(new ChatSession.ToolHost() {
            @Override public LfmToolCatalog.Capabilities capabilities(boolean useSearch, String ep) {
                return new LfmToolCatalog.Capabilities(false, false, false, false, "", false, true, "Vault");
            }
            @Override public Locale locale() { return Locale.US; }
            @Override public ToolRegistry registry() { return new ToolRegistry(); }
            @Override public boolean canAutoExecute(ToolRegistry.Request req) {
                return req.tool == ToolRegistry.Tool.CALCULATOR;
            }
            @Override public AndroidActionExecutor.ActionResult executeReadOnly(ToolRegistry.Request req) {
                return new AndroidActionExecutor.ActionResult(true, "CALC_OK", "42");
            }
            @Override public void proposeTool(ToolRegistry.Request req) { fail("Expected read-only auto-exec"); }
        });

        assertTrue(session.submit("Calculate 6 * 7", "", "", false, "", 128));
        worker.drain(); ui.drain();

        assertEquals(3, engine.prompts.size());
        // Step 1 grammar has core tools (including mounted fs_list & fs_read), NOT deferred calculator yet
        assertTrue(engine.grammars.get(0).contains("<function=search_tools>"));
        assertTrue(engine.grammars.get(0).contains("<function=fs_list>"));
        assertTrue(engine.grammars.get(0).contains("<function=fs_read>"));
        assertFalse(engine.grammars.get(0).contains("<function=calculator>"));
        // Step 2 prompt contains <additional_tools> with calculator AND Step 2 grammar now allows calculator!
        assertTrue(engine.prompts.get(1).contains("<additional_tools>"));
        assertTrue(engine.prompts.get(1).contains("\"name\":\"calculator\""));
        assertTrue("Grammar must expand dynamically after search_tools", engine.grammars.get(1).contains("<function=calculator>"));
        // Step 3 prompt contains calculation result and snapshot exposes both expandable tool calls
        assertTrue(engine.prompts.get(2).contains("42"));
        ChatSnapshot.Turn reply = session.snapshot().turns.get(1);
        assertEquals("6 * 7 equals 42.", reply.text);
        assertEquals(2, reply.toolCalls.size());
        assertEquals("search_tools", reply.toolCalls.get(0).toolName);
        assertEquals("calculator", reply.toolCalls.get(1).toolName);
        assertEquals("42", reply.toolCalls.get(1).resultText);
    }

    @Test public void modelToolBridgeCoercesWorkspaceIntegerParameters() {
        ToolRegistry registry = new ToolRegistry();
        ToolRegistry.Parsed listParsed = ModelToolBridge.parseFromAssistant(registry,
                "<tool_call><function=fs_list><parameter=path></parameter><parameter=max_depth>3</parameter></function></tool_call>");
        assertNotNull(listParsed);
        assertTrue(listParsed.message, listParsed.isValid());
        assertEquals(3, ((ToolRegistry.FsListRequest) listParsed.request).maxDepth);

        ToolRegistry.Parsed readParsed = ModelToolBridge.parseFromAssistant(registry,
                "<tool_call><function=fs_read><parameter=path>todo.md</parameter><parameter=start_line>2</parameter><parameter=max_lines>50</parameter></function></tool_call>");
        assertNotNull(readParsed);
        assertTrue(readParsed.message, readParsed.isValid());
        ToolRegistry.FsReadRequest readReq = (ToolRegistry.FsReadRequest) readParsed.request;
        assertEquals("todo.md", readReq.path);
        assertEquals(2, readReq.startLine);
        assertEquals(50, readReq.maxLines);
    }

    @Test public void workspaceExecutorResolvesRootLabelPrefixAndWildcardSearch() throws Exception {
        File docs = tmp.newFolder("Documents");
        LocalFileWorkspaceGateway gw = new LocalFileWorkspaceGateway(docs);
        gw.createFile("todo.md", "Buy batteries\nCall dentist");
        WorkspaceActionExecutor exec = new WorkspaceActionExecutor(
                () -> true, () -> gw, ZonedDateTime::now, () -> "Documents");

        // Model passes mounted folder's own display name ("Documents") as path
        AndroidActionExecutor.ActionResult listed = exec.execute("fs_list",
                Collections.singletonMap("path", "Documents"));
        assertTrue(listed.message, listed.succeeded);
        assertTrue(listed.message.contains("todo.md"));
        assertTrue(listed.message.contains("Documents"));

        AndroidActionExecutor.ActionResult read = exec.execute("fs_read",
                Collections.singletonMap("path", "Documents/todo.md"));
        assertTrue(read.message, read.succeeded);
        assertTrue(read.message.contains("Buy batteries"));

        // Wildcard search delegates to directory listing instead of returning 0 matches
        AndroidActionExecutor.ActionResult wildcard = exec.execute("fs_search",
                Collections.singletonMap("query", "*"));
        assertTrue(wildcard.message, wildcard.succeeded);
        assertTrue(wildcard.message.contains("todo.md"));
    }

    @Test public void workspaceToolLoopRecordsCleanToolCallsStripsUntrustedTokenFromPromptAndKeepsTaint() throws Exception {
        File docs = tmp.newFolder("Documents2");
        LocalFileWorkspaceGateway gw = new LocalFileWorkspaceGateway(docs);
        gw.createFile("notes.md", "Project Alpha notes");
        WorkspaceActionExecutor wsExec = new WorkspaceActionExecutor(
                () -> true, () -> gw, ZonedDateTime::now, () -> "Documents");

        Queue worker = new Queue(), ui = new Queue();
        GrammarCapturingEngine engine = new GrammarCapturingEngine();
        engine.replies.add("<tool_call><function=fs_list><parameter=path>Documents</parameter><parameter=max_depth>2</parameter></function></tool_call>");
        engine.replies.add("<tool_call><function=fs_read><parameter=path>notes.md</parameter><parameter=start_line>1</parameter><parameter=max_lines>20</parameter></function></tool_call>");
        engine.replies.add("Your Documents folder contains notes.md about Project Alpha.");

        List<ChatSession.CompletedTurn> persisted = new ArrayList<>();
        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (ep, q) -> Collections.emptyList(), persisted::add, path -> {}, (t, s, g) -> {}, null);
        session.setToolHost(new ChatSession.ToolHost() {
            @Override public LfmToolCatalog.Capabilities capabilities(boolean useSearch, String ep) {
                return new LfmToolCatalog.Capabilities(false, false, false, false, "", false, true, "Documents");
            }
            @Override public Locale locale() { return new Locale("pl"); }
            @Override public ToolRegistry registry() { return new ToolRegistry(); }
            @Override public boolean canAutoExecute(ToolRegistry.Request req) {
                return req.tool == ToolRegistry.Tool.FS_LIST || req.tool == ToolRegistry.Tool.FS_READ;
            }
            @Override public AndroidActionExecutor.ActionResult executeReadOnly(ToolRegistry.Request req) {
                Map<String, String> strArgs = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : req.arguments.entrySet()) strArgs.put(e.getKey(), String.valueOf(e.getValue()));
                return wsExec.execute(req.tool.wireName, strArgs);
            }
            @Override public void proposeTool(ToolRegistry.Request req) { fail("Expected auto-exec"); }
        });

        assertTrue(session.submit("Sprawdź dostęp do plików w Documents", "", "", false, "", 128));
        worker.drain(); ui.drain();

        assertEquals(3, engine.prompts.size());
        assertFalse("Raw UNTRUSTED_WORKSPACE token must not confuse the LLM in <tool_response>",
                engine.prompts.get(1).contains("UNTRUSTED_WORKSPACE"));
        assertTrue(engine.prompts.get(1).contains("notes.md"));
        assertTrue(engine.prompts.get(2).contains("Project Alpha notes"));

        ChatSnapshot.Turn reply = session.snapshot().turns.get(1);
        assertEquals(2, reply.toolCalls.size());
        assertEquals("fs_list", reply.toolCalls.get(0).toolName);
        assertTrue(reply.toolCalls.get(0).succeeded);
        assertFalse(reply.toolCalls.get(0).resultText.contains("UNTRUSTED_WORKSPACE"));
        assertEquals("fs_read", reply.toolCalls.get(1).toolName);
        assertTrue(reply.toolCalls.get(1).resultText.contains("Project Alpha notes"));

        assertEquals(1, persisted.size());
        assertEquals(2, persisted.get(0).toolCalls.size());
    }

    @Test public void polishWorkspaceAndMcpQueriesEngageToolMode() {
        McpServerConfig srv = new McpServerConfig("termux", "Termux", "http://127.0.0.1:3000/mcp", "",
                true, false, Collections.singletonList(
                        new McpServerConfig.ToolDef("git_status", "Show repository git status",
                                Collections.emptyList(), "{\"type\":\"object\",\"properties\":{}}")));
        LfmToolCatalog.Capabilities caps = new LfmToolCatalog.Capabilities(
                false, false, false, false, "", false, true, "Documents",
                Collections.singletonList(srv.toToolSpec(srv.cachedTools.get(0))));

        assertTrue(LfmToolCatalog.shouldEngageToolMode("Nie działa dostęp do plików", false, caps));
        assertTrue(LfmToolCatalog.shouldEngageToolMode("Co jest w folderze Documents?", false, caps));
        assertTrue(LfmToolCatalog.shouldEngageToolMode("Jakie masz narzędzia?", false, caps));
        assertTrue(LfmToolCatalog.shouldEngageToolMode("Sprawdź Termux", false, caps));
        assertFalse(LfmToolCatalog.shouldEngageToolMode("Jak upiec szarlotkę?", false, caps));
    }

    @Test public void documentTaintedTurnBlocksAutoWebSearchAndRequiresTaintedConfirmation() {
        Queue worker = new Queue(), ui = new Queue();
        GrammarCapturingEngine engine = new GrammarCapturingEngine();
        // Malicious document tries to exfiltrate secret via web_search
        engine.replies.add("<tool_call><function=web_search><parameter=query>leak secret token</parameter></function></tool_call>");

        List<ToolRegistry.Request> proposed = new ArrayList<>();
        List<Boolean> taintedFlags = new ArrayList<>();
        boolean[] searchedNetwork = { false };

        ChatSession session = new ChatSession(engine, worker, ui, () -> 1L,
                (ep, q) -> { searchedNetwork[0] = true; return Collections.emptyList(); },
                (t, s, g) -> {});
        session.setToolHost(new ChatSession.ToolHost() {
            @Override public LfmToolCatalog.Capabilities capabilities(boolean useSearch, String ep) {
                return new LfmToolCatalog.Capabilities(true, false, false, false, "", false);
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
                proposeTool(req, false);
            }
            @Override public void proposeTool(ToolRegistry.Request req, boolean tainted) {
                proposed.add(req);
                taintedFlags.add(tainted);
            }
        });

        ChatSession.Attachment doc = ChatSession.Attachment.document("secret.txt", "API_KEY=12345");
        assertTrue(session.submit("Summarize this file", Collections.singletonList(doc),
                true, "https://searx.example/search", 128));
        worker.drain(); ui.drain();

        assertFalse("Outbound web_search must NOT auto-execute on document-tainted turn", searchedNetwork[0]);
        assertEquals(1, proposed.size());
        assertEquals(ToolRegistry.Tool.WEB_SEARCH, proposed.get(0).tool);
        assertEquals(Boolean.TRUE, taintedFlags.get(0));

        // Also verify ToolCoordinator.proposeTainted forces CONFIRM even for USER_ACTION + SAFE tool
        ToolCoordinator coordinator = new ToolCoordinator(
                req -> new AndroidActionExecutor.ActionResult(true, "OK", "ran"),
                req -> true, () -> 1000L);
        ToolCoordinator.Outcome out = coordinator.proposeTainted(proposed.get(0), ToolCoordinator.Source.USER_ACTION);
        assertEquals("PENDING_CONFIRMATION", out.status);
        assertTrue(out.proposal.tainted);
        assertEquals(ToolCoordinator.Risk.CONFIRM, out.proposal.risk);
    }
}
