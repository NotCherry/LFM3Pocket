package com.notcherry.lfm3pocket;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

/** Unit tests for Sprint 3: offline Contacts lookup and Custom Local Intent/URI Shortcuts. */
public final class ContactsAndShortcutsTest {
    @Test public void contactsExecutorChecksPermissionAndFormatsUntrustedResults() {
        ContactsGateway gw = (query, limit) -> Arrays.asList(
                new ContactsGateway.ContactMatch("Anna Kowalska", "+48 600 111 222", "anna@example.org"));

        ContactsActionExecutor denied = new ContactsActionExecutor(() -> false, gw);
        AndroidActionExecutor.ActionResult noPerm = denied.execute("lookup_contact", Collections.singletonMap("query", "Anna"));
        assertFalse(noPerm.succeeded);
        assertEquals("PERMISSION_DENIED", noPerm.status);

        ContactsActionExecutor granted = new ContactsActionExecutor(() -> true, gw);
        AndroidActionExecutor.ActionResult ok = granted.execute("lookup_contact", Collections.singletonMap("query", "Anna"));
        assertTrue(ok.succeeded);
        assertTrue(ok.message.startsWith("UNTRUSTED_CONTACTS"));
        assertTrue(ok.message.contains("Anna Kowalska"));
        assertTrue(ok.message.contains("+48 600 111 222"));
        assertTrue(ok.message.contains("anna@example.org"));

        ToolRegistry.Parsed parsed = new ToolRegistry().parse(
                "{\"tool\":\"lookup_contact\",\"arguments\":{\"query\":\"Anna\"}}");
        assertTrue(parsed.isValid());
        assertEquals(5, ((ToolRegistry.LookupContactRequest) parsed.request).limit);
    }

    @Test public void customShortcutRejectsUnsafeSchemesAndRendersEncodedUri() {
        assertThrows(IllegalArgumentException.class, () ->
                new CustomShortcut("evil1", "Evil", "desc", "javascript:alert(1)", ""));
        assertThrows(IllegalArgumentException.class, () ->
                new CustomShortcut("evil2", "Evil", "desc", "file:///etc/passwd", ""));
        assertThrows(IllegalArgumentException.class, () ->
                new CustomShortcut("evil3", "Evil", "desc", "content://contacts/1", ""));
        assertThrows(IllegalArgumentException.class, () ->
                new CustomShortcut("evil4", "Evil", "desc", "intent:#Intent;end", ""));

        CustomShortcut s = new CustomShortcut("Obsidian Note", "Obsidian Note",
                "Create a note inside Obsidian",
                "obsidian://new?name={{title}}&content={{content}}", "md.obsidian");
        assertEquals("obsidian_note", s.id);
        assertEquals("shortcut.obsidian_note", s.wireName());
        assertEquals(Arrays.asList("title", "content"), s.placeholders);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("title", "Daily Plan");
        args.put("content", "Buy coffee & milk");
        String rendered = s.renderUri(args);
        assertEquals("obsidian://new?name=Daily%20Plan&content=Buy%20coffee%20%26%20milk", rendered);
    }

    @Test public void customShortcutStoreIntegratesWithCatalogRegistryAndCoordinator() {
        String[] persisted = { "[]" };
        List<String> launched = new ArrayList<>();
        CustomShortcutStore store = new CustomShortcutStore(
                () -> persisted[0],
                json -> persisted[0] = json,
                (uri, pkg) -> launched.add(pkg + "|" + uri));

        store.addOrReplace(new CustomShortcut("obsidian_note", "Obsidian Note",
                "Save note in Obsidian app",
                "obsidian://new?name={{title}}&content={{body}}", "md.obsidian"));

        assertEquals(1, store.list().size());
        LfmToolCatalog.Capabilities caps = new LfmToolCatalog.Capabilities(
                false, false, false, false, "", false, false, "", store.discoverTools());
        LfmToolCatalog.SearchResult sr = LfmToolCatalog.searchTools("obsidian", caps, 5);
        assertTrue(sr.formattedToolResponse.contains("\"name\":\"shortcut.obsidian_note\""));
        assertTrue(LfmToolGrammar.build(sr.matched).contains("<function=shortcut.obsidian_note>"));

        ToolRegistry registry = new ToolRegistry(() -> 1000L, Collections.singletonList(store));
        assertFalse("Unregistered shortcut must fail closed",
                registry.parse("{\"tool\":\"shortcut.unknown\",\"arguments\":{}}").isValid());

        ToolRegistry.Parsed parsed = registry.parse(
                "{\"tool\":\"shortcut.obsidian_note\",\"arguments\":{\"title\":\"Ideas\",\"body\":\"Offline first\"}}");
        assertTrue(parsed.message, parsed.isValid());
        assertEquals(ToolRegistry.Tool.EXTERNAL, parsed.request.tool);

        String card = ToolCoordinator.formatForDisplay(parsed.request, "");
        assertTrue(card.contains("Obsidian Note"));
        assertTrue(card.contains("obsidian://new?name=Ideas&content=Offline%20first"));

        ToolCoordinator coordinator = new ToolCoordinator(
                req -> {
                    ToolRegistry.ExternalToolRequest ext = (ToolRegistry.ExternalToolRequest) req;
                    return store.execute(ext.externalWireName, ext.arguments);
                },
                req -> true, () -> 1000L);
        ToolCoordinator.Outcome pending = coordinator.propose(parsed.request, ToolCoordinator.Source.MODEL_PROPOSAL);
        assertEquals("PENDING_CONFIRMATION", pending.status);
        assertEquals(ToolCoordinator.Risk.CONFIRM, pending.proposal.risk);
        assertTrue("Must not launch before user approves", launched.isEmpty());

        ToolCoordinator.Outcome approved = coordinator.approve(pending.proposal.id);
        assertTrue(approved.result.succeeded);
        assertEquals(1, launched.size());
        assertEquals("md.obsidian|obsidian://new?name=Ideas&content=Offline%20first", launched.get(0));
    }
}
