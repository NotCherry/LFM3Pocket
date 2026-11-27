package com.notcherry.lfm3pocket;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Settings card for Sprint 3 external capabilities:
 * <ol>
 *   <li>Offline Contacts lookup permission ({@code READ_CONTACTS})</li>
 *   <li>Custom offline local URI/Intent shortcuts (Obsidian, Tasker, geo, etc.)</li>
 *   <li>Open MCP Streamable HTTP servers (local Termux loopback, LAN homelab, or HTTPS remote)</li>
 * </ol>
 */
final class ExternalToolsSettingsSection {
    static final class Deps {
        final Supplier<CustomShortcutStore> shortcutStore;
        final Supplier<McpToolProvider> mcpProvider;
        final Runnable requestContactsPermission;
        final ExecutorService worker;
        final Consumer<Runnable> runOnUiThread;
        final Consumer<CharSequence> chatStatus;

        Deps(Supplier<CustomShortcutStore> shortcutStore,
             Supplier<McpToolProvider> mcpProvider,
             Runnable requestContactsPermission,
             ExecutorService worker,
             Consumer<Runnable> runOnUiThread,
             Consumer<CharSequence> chatStatus) {
            this.shortcutStore = shortcutStore;
            this.mcpProvider = mcpProvider;
            this.requestContactsPermission = requestContactsPermission;
            this.worker = worker;
            this.runOnUiThread = runOnUiThread;
            this.chatStatus = chatStatus;
        }
    }

    private final MainActivitySettingsHost host;
    private final Deps deps;
    private TextView contactsStatus;
    private LinearLayout shortcutsList;
    private LinearLayout mcpList;

    ExternalToolsSettingsSection(MainActivitySettingsHost host, Deps deps) {
        this.host = host;
        this.deps = deps;
    }

    void mount(LinearLayout configuration, int ink) {
        LinearLayout card = StillwaterUi.settingsCard(configuration,
                host.getString(R.string.external_tools_heading));
        StillwaterUi.settingsHelp(card, host.getString(R.string.external_tools_help));

        // 1. Offline Contacts lookup (opt-in READ_CONTACTS)
        contactsStatus = host.label(host.getString(R.string.contacts_status_off), 14, ink);
        contactsStatus.setId(R.id.settings_contacts_status);
        contactsStatus.setPadding(0, host.dp(2), 0, host.dp(6));
        StillwaterReadable.prepareWrappingLabel(contactsStatus);
        card.addView(contactsStatus);

        Button grantContacts = host.button(host.getString(R.string.contacts_grant_button));
        grantContacts.setId(R.id.settings_contacts_grant);
        StillwaterUi.outlined(grantContacts);
        grantContacts.setOnClickListener(v -> deps.requestContactsPermission.run());
        StillwaterReadable.addFlexibleFullWidth(card, grantContacts, 48);

        StillwaterUi.settingsDivider(card);

        // 2. Offline Custom Local Shortcuts
        TextView scHeading = host.label(host.getString(R.string.shortcuts_subheading), 15, ink);
        scHeading.setTypeface(StillwaterUi.sansMedium(host.settingsActivity()));
        StillwaterReadable.prepareWrappingLabel(scHeading);
        card.addView(scHeading);
        StillwaterUi.settingsHelp(card, host.getString(R.string.shortcuts_help));

        shortcutsList = new LinearLayout(host.settingsActivity());
        shortcutsList.setOrientation(LinearLayout.VERTICAL);
        card.addView(shortcutsList, new LinearLayout.LayoutParams(-1, -2));

        Button addShortcut = host.button(host.getString(R.string.shortcuts_add_button));
        addShortcut.setId(R.id.settings_shortcut_add);
        StillwaterUi.outlined(addShortcut);
        addShortcut.setOnClickListener(v -> showAddShortcutDialog());
        StillwaterReadable.addFlexibleFullWidth(card, addShortcut, 48);

        StillwaterUi.settingsDivider(card);

        // 3. Open MCP Servers (Local Termux / LAN / HTTPS)
        TextView mcpHeading = host.label(host.getString(R.string.mcp_subheading), 15, ink);
        mcpHeading.setTypeface(StillwaterUi.sansMedium(host.settingsActivity()));
        StillwaterReadable.prepareWrappingLabel(mcpHeading);
        card.addView(mcpHeading);
        StillwaterUi.settingsHelp(card, host.getString(R.string.mcp_help));

        mcpList = new LinearLayout(host.settingsActivity());
        mcpList.setOrientation(LinearLayout.VERTICAL);
        card.addView(mcpList, new LinearLayout.LayoutParams(-1, -2));

        Button addMcp = host.button(host.getString(R.string.mcp_add_button));
        addMcp.setId(R.id.settings_mcp_add);
        StillwaterUi.outlined(addMcp);
        addMcp.setOnClickListener(v -> showAddMcpDialog());
        StillwaterReadable.addFlexibleFullWidth(card, addMcp, 48);
    }

    void refreshStatus() {
        if (contactsStatus != null) {
            boolean granted = host.settingsActivity().checkSelfPermission(Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED;
            contactsStatus.setText(host.getString(
                    granted ? R.string.contacts_status_ready : R.string.contacts_status_off));
        }
        renderShortcuts();
        renderMcpServers();
    }

    private void renderShortcuts() {
        if (shortcutsList == null) return;
        shortcutsList.removeAllViews();
        List<CustomShortcut> items = deps.shortcutStore.get().list();
        if (items.isEmpty()) {
            TextView empty = host.label(host.getString(R.string.shortcuts_empty), 13, StillwaterUi.MUTED);
            empty.setPadding(0, host.dp(4), 0, host.dp(8));
            shortcutsList.addView(empty);
            return;
        }
        for (CustomShortcut s : items) {
            LinearLayout row = new LinearLayout(host.settingsActivity());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, host.dp(4), 0, host.dp(8));
            TextView info = host.label(s.label + " (" + s.wireName() + ")\n" + s.uriTemplate, 13, StillwaterUi.INK);
            StillwaterReadable.prepareWrappingLabel(info);
            row.addView(info);
            Button remove = host.button(host.getString(R.string.remove_item));
            StillwaterUi.dangerOutlined(remove);
            remove.setOnClickListener(v -> {
                deps.shortcutStore.get().remove(s.id);
                renderShortcuts();
                deps.chatStatus.accept(host.getString(R.string.shortcuts_removed, s.label));
            });
            StillwaterReadable.addFlexibleFullWidth(row, remove, 44);
            shortcutsList.addView(row);
        }
    }

    private void renderMcpServers() {
        if (mcpList == null) return;
        mcpList.removeAllViews();
        List<McpServerConfig> servers = deps.mcpProvider.get().listServers();
        if (servers.isEmpty()) {
            TextView empty = host.label(host.getString(R.string.mcp_empty), 13, StillwaterUi.MUTED);
            empty.setPadding(0, host.dp(4), 0, host.dp(8));
            mcpList.addView(empty);
            return;
        }
        for (McpServerConfig server : servers) {
            LinearLayout row = new LinearLayout(host.settingsActivity());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, host.dp(4), 0, host.dp(8));
            String summary = server.label + " · " + server.cachedTools.size() + " tools\n" + server.url;
            TextView info = host.label(summary, 13, StillwaterUi.INK);
            StillwaterReadable.prepareWrappingLabel(info);
            row.addView(info);

            Button syncBtn = host.button(host.getString(R.string.mcp_sync_button));
            StillwaterUi.outlined(syncBtn);
            syncBtn.setOnClickListener(v -> syncServer(server, syncBtn));

            Button removeBtn = host.button(host.getString(R.string.remove_item));
            StillwaterUi.dangerOutlined(removeBtn);
            removeBtn.setOnClickListener(v -> {
                deps.mcpProvider.get().remove(server.id);
                renderMcpServers();
                deps.chatStatus.accept(host.getString(R.string.mcp_removed, server.label));
            });
            StillwaterReadable.addFlexibleButtonRow(row, host.settingsActivity(), 44, syncBtn, removeBtn);
            mcpList.addView(row);
        }
    }

    private void syncServer(McpServerConfig server, Button button) {
        button.setEnabled(false);
        deps.chatStatus.accept(host.getString(R.string.mcp_syncing, server.label));
        deps.worker.execute(() -> {
            try {
                List<McpServerConfig.ToolDef> tools = deps.mcpProvider.get().syncServer(server.id);
                deps.runOnUiThread.accept(() -> {
                    button.setEnabled(true);
                    renderMcpServers();
                    deps.chatStatus.accept(host.getString(R.string.mcp_sync_ok, server.label, tools.size()));
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                deps.runOnUiThread.accept(() -> {
                    button.setEnabled(true);
                    deps.chatStatus.accept(host.getString(R.string.mcp_sync_failed, server.label, msg));
                });
            }
        });
    }

    private void showAddShortcutDialog() {
        LinearLayout form = new LinearLayout(host.settingsActivity());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(host.dp(18), host.dp(10), host.dp(18), host.dp(4));

        EditText nameField = boxedInput(host.getString(R.string.shortcuts_name_hint), InputType.TYPE_CLASS_TEXT);
        EditText uriField = boxedInput(host.getString(R.string.shortcuts_uri_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText descField = boxedInput(host.getString(R.string.shortcuts_desc_hint), InputType.TYPE_CLASS_TEXT);
        EditText pkgField = boxedInput(host.getString(R.string.shortcuts_pkg_hint), InputType.TYPE_CLASS_TEXT);
        form.addView(nameField);
        form.addView(uriField);
        form.addView(descField);
        form.addView(pkgField);

        StillwaterUi.showDialog(new AlertDialog.Builder(host.settingsActivity())
                .setTitle(R.string.shortcuts_add_button)
                .setView(form)
                .setPositiveButton(R.string.save, (d, w) -> {
                    try {
                        String label = textOf(nameField);
                        String uri = textOf(uriField);
                        String desc = textOf(descField);
                        String pkg = textOf(pkgField);
                        CustomShortcut shortcut = new CustomShortcut(label, label, desc, uri, pkg);
                        deps.shortcutStore.get().addOrReplace(shortcut);
                        renderShortcuts();
                        deps.chatStatus.accept(host.getString(R.string.shortcuts_saved, shortcut.label));
                    } catch (RuntimeException e) {
                        deps.chatStatus.accept(e.getMessage() == null ? "Invalid shortcut." : e.getMessage());
                    }
                })
                .setNegativeButton(R.string.cancel, null));
    }

    private void showAddMcpDialog() {
        LinearLayout form = new LinearLayout(host.settingsActivity());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(host.dp(18), host.dp(10), host.dp(18), host.dp(4));

        EditText labelField = boxedInput(host.getString(R.string.mcp_label_hint), InputType.TYPE_CLASS_TEXT);
        EditText urlField = boxedInput(host.getString(R.string.mcp_url_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText tokenField = boxedInput(host.getString(R.string.mcp_token_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox lanBox = new CheckBox(host.settingsActivity());
        lanBox.setText(R.string.mcp_allow_lan_http);
        StillwaterUi.styleCheck(lanBox);
        form.addView(labelField);
        form.addView(urlField);
        form.addView(tokenField);
        form.addView(lanBox);

        StillwaterUi.showDialog(new AlertDialog.Builder(host.settingsActivity())
                .setTitle(R.string.mcp_add_button)
                .setView(form)
                .setPositiveButton(R.string.save, (d, w) -> {
                    try {
                        String label = textOf(labelField);
                        String url = textOf(urlField);
                        String token = textOf(tokenField);
                        McpServerConfig cfg = new McpServerConfig(
                                label, label, url, token, true, lanBox.isChecked(), null);
                        deps.mcpProvider.get().addOrReplace(cfg);
                        renderMcpServers();
                        deps.chatStatus.accept(host.getString(R.string.mcp_saved, cfg.label));
                    } catch (RuntimeException e) {
                        deps.chatStatus.accept(e.getMessage() == null ? "Invalid MCP server." : e.getMessage());
                    }
                })
                .setNegativeButton(R.string.cancel, null));
    }

    private EditText boxedInput(String hint, int inputType) {
        EditText field = new EditText(host.settingsActivity());
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine(true);
        StillwaterUi.styleBoxedInput(field);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = host.dp(8);
        field.setLayoutParams(lp);
        return field;
    }

    private static String textOf(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }
}
