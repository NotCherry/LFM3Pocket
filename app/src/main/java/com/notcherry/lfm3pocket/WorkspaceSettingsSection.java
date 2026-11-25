package com.notcherry.lfm3pocket;

import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Settings card for picking and clearing an offline Local Workspace / Markdown vault folder
 * via Android Storage Access Framework ({@code ACTION_OPEN_DOCUMENT_TREE}).
 */
final class WorkspaceSettingsSection {
    static final class Deps {
        final Supplier<RuntimePreferences> preferences;
        final Runnable pickWorkspaceFolder;
        final Consumer<CharSequence> chatStatus;

        Deps(Supplier<RuntimePreferences> preferences, Runnable pickWorkspaceFolder,
             Consumer<CharSequence> chatStatus) {
            this.preferences = preferences;
            this.pickWorkspaceFolder = pickWorkspaceFolder;
            this.chatStatus = chatStatus;
        }
    }

    private final MainActivitySettingsHost host;
    private final Deps deps;
    private TextView statusText;

    WorkspaceSettingsSection(MainActivitySettingsHost host, Deps deps) {
        this.host = host;
        this.deps = deps;
    }

    void mount(LinearLayout configuration, int ink) {
        LinearLayout card = StillwaterUi.settingsCard(configuration,
                host.getString(R.string.workspace_heading));
        StillwaterUi.settingsHelp(card, host.getString(R.string.workspace_help));

        statusText = host.label(host.getString(R.string.workspace_none_picked), 14, ink);
        statusText.setId(R.id.settings_workspace_status);
        statusText.setPadding(0, host.dp(2), 0, host.dp(8));
        StillwaterReadable.prepareWrappingLabel(statusText);
        card.addView(statusText);

        Button pickButton = host.button(host.getString(R.string.workspace_pick_button));
        pickButton.setId(R.id.settings_workspace_pick);
        StillwaterUi.outlined(pickButton);
        pickButton.setOnClickListener(v -> deps.pickWorkspaceFolder.run());

        Button clearButton = host.button(host.getString(R.string.workspace_clear_button));
        clearButton.setId(R.id.settings_workspace_clear);
        StillwaterUi.dangerOutlined(clearButton);
        clearButton.setOnClickListener(v -> clearWorkspace());

        StillwaterReadable.addFlexibleButtonRow(card, host.settingsActivity(), 48, pickButton, clearButton);
    }

    void refreshStatus() {
        if (statusText == null) return;
        RuntimePreferences prefs = deps.preferences.get();
        String uri = prefs.workspaceTreeUri();
        if (uri == null || uri.trim().isEmpty()) {
            statusText.setText(host.getString(R.string.workspace_none_picked));
            return;
        }
        String label = prefs.workspaceLabel();
        if (label == null || label.trim().isEmpty()) label = uri.trim();
        statusText.setText(host.getString(R.string.workspace_picked, label));
    }

    void onFolderPicked(Uri treeUri, int intentFlags) {
        if (treeUri == null) return;
        int takeFlags = intentFlags
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (takeFlags == 0) {
            takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        try {
            host.settingsActivity().getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
        } catch (SecurityException ignored) {
            // Some SAF providers do not support both flags; try read+write explicitly.
            try {
                host.settingsActivity().getContentResolver().takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored2) {}
        }
        String label = AndroidWorkspaceGateway.queryTreeLabel(host.settingsActivity(), treeUri);
        deps.preferences.get().setWorkspaceTarget(treeUri.toString(), label);
        refreshStatus();
        deps.chatStatus.accept(host.getString(R.string.workspace_picked, label));
    }

    private void clearWorkspace() {
        RuntimePreferences prefs = deps.preferences.get();
        String existing = prefs.workspaceTreeUri();
        if (existing != null && !existing.trim().isEmpty()) {
            try {
                host.settingsActivity().getContentResolver().releasePersistableUriPermission(
                        Uri.parse(existing),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (RuntimeException ignored) {}
        }
        prefs.setWorkspaceTarget("", "");
        refreshStatus();
        deps.chatStatus.accept(host.getString(R.string.workspace_cleared));
    }
}
