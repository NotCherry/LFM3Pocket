package com.notcherry.lfm3pocket;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Primary chat viewport: top bar, status line, ChatPanel, and media controls. */
final class ChatShellSection {
    static final class ChatPanelDeps {
        final Consumer<String> send;
        final Runnable stopActiveWork;
        final Runnable onNewChat;
        final Consumer<String> onRetryTurn;
        final Runnable requestCompact;
        final Consumer<ChatSnapshot.AttachmentRef> openAttachment;
        final Runnable clearActiveDocuments;
        final Runnable clearActiveImages;

        ChatPanelDeps(Consumer<String> send, Runnable stopActiveWork, Runnable onNewChat,
                Consumer<String> onRetryTurn, Runnable requestCompact) {
            this(send, stopActiveWork, onNewChat, onRetryTurn, requestCompact, ref -> {}, () -> {}, () -> {});
        }

        ChatPanelDeps(Consumer<String> send, Runnable stopActiveWork, Runnable onNewChat,
                Consumer<String> onRetryTurn, Runnable requestCompact,
                Consumer<ChatSnapshot.AttachmentRef> openAttachment,
                Runnable clearActiveDocuments, Runnable clearActiveImages) {
            this.send = send;
            this.stopActiveWork = stopActiveWork;
            this.onNewChat = onNewChat;
            this.onRetryTurn = onRetryTurn;
            this.requestCompact = requestCompact;
            this.openAttachment = openAttachment != null ? openAttachment : ref -> {};
            this.clearActiveDocuments = clearActiveDocuments != null ? clearActiveDocuments : () -> {};
            this.clearActiveImages = clearActiveImages != null ? clearActiveImages : () -> {};
        }
    }

    static final class Deps {
        final Supplier<String> historyTitle;
        final BooleanSupplier searchEnabled;
        final Runnable requestNewChat;
        final Runnable openSettings;
        final Runnable showSearchSettings;
        final ChatPanelDeps chatPanel;

        Deps(Supplier<String> historyTitle, BooleanSupplier searchEnabled, Runnable requestNewChat,
                Runnable openSettings, Runnable showSearchSettings, ChatPanelDeps chatPanel) {
            this.historyTitle = historyTitle;
            this.searchEnabled = searchEnabled;
            this.requestNewChat = requestNewChat;
            this.openSettings = openSettings;
            this.showSearchSettings = showSearchSettings;
            this.chatPanel = chatPanel;
        }
    }

    private final SettingsUiFactory ui;
    private final Deps deps;
    private View topBarView;
    private View topDividerView;
    private TextView brandTitle;
    private TextView modePill;
    private TextView status;
    private View chatShell;
    private ChatPanel chatPanel;
    private Button photoButton;
    private Button cameraButton;
    private Button documentButton;
    private Button recordButton;
    private Button speakButton;

    ChatShellSection(SettingsUiFactory ui, Deps deps) {
        this.ui = ui;
        this.deps = deps;
    }

    void mountChrome(LinearLayout root, int ink, int inkMuted, int accent) {
        int minTouch = InterfaceSize.minTouchPx(ui.settingsActivity());

        LinearLayout topBar = new LinearLayout(ui.settingsActivity());
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, ui.dp(6), 0, ui.dp(8));

        LinearLayout titles = new LinearLayout(ui.settingsActivity());
        titles.setOrientation(LinearLayout.VERTICAL);
        brandTitle = ui.label(deps.historyTitle.get(), 20, ink);
        brandTitle.setTypeface(StillwaterUi.serif(ui.settingsActivity()));
        StillwaterReadable.prepareWrappingLabel(brandTitle);
        titles.addView(brandTitle);

        // Subtle search indicator only when web search is active (avoids duplicating composer mode line).
        modePill = ui.label(ui.getString(R.string.mode_web_search_short), 12, accent);
        modePill.setTypeface(StillwaterUi.sansMedium(ui.settingsActivity()));
        StillwaterReadable.prepareWrappingLabel(modePill);
        modePill.setVisibility(deps.searchEnabled.getAsBoolean() ? View.VISIBLE : View.GONE);
        titles.addView(modePill);

        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titlesParams.rightMargin = ui.dp(8);
        topBar.addView(titles, titlesParams);

        Button newChat = ui.button(ui.getString(R.string.new_chat));
        newChat.setId(R.id.chat_new_chat);
        StillwaterUi.accentOutlined(newChat);
        StillwaterReadable.setTextSizeSp(newChat, 13.5f);
        newChat.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        newChat.setOnClickListener(v -> deps.requestNewChat.run());
        newChat.setMinWidth(minTouch);
        newChat.setMinimumWidth(minTouch);
        newChat.setMinHeight(minTouch);
        newChat.setMinimumHeight(minTouch);
        StillwaterReadable.prepareWrappingControl(newChat, minTouch);
        LinearLayout.LayoutParams newChatParams = new LinearLayout.LayoutParams(-2, -2);
        newChatParams.rightMargin = ui.dp(6);
        topBar.addView(newChat, newChatParams);

        Button overflow = ui.button("⋮");
        overflow.setId(R.id.chat_overflow);
        overflow.setContentDescription(ui.getString(R.string.chat_more_options));
        StillwaterReadable.setTextSizeSp(overflow, 20);
        overflow.setGravity(Gravity.CENTER);
        overflow.setPadding(0, 0, 0, 0);
        StillwaterUi.outlined(overflow);
        overflow.setOnClickListener(v -> {
            android.view.ContextThemeWrapper menuContext =
                    new android.view.ContextThemeWrapper(ui.settingsActivity(), R.style.Theme_Stillwater);
            android.widget.PopupMenu menu = new android.widget.PopupMenu(menuContext, v);
            menu.getMenu().add(0, 1, 0, ui.getString(R.string.history));
            menu.getMenu().add(0, 2, 1, ui.getString(R.string.web_search_menu));
            menu.getMenu().add(0, 3, 2, ui.getString(R.string.settings_title));
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    ui.settingsActivity().startActivity(
                            new Intent(ui.settingsActivity(), HistoryActivity.class));
                } else if (item.getItemId() == 2) {
                    deps.showSearchSettings.run();
                } else if (item.getItemId() == 3) {
                    deps.openSettings.run();
                }
                return true;
            });
            menu.show();
        });
        overflow.setMinWidth(minTouch);
        overflow.setMinimumWidth(minTouch);
        overflow.setMinHeight(minTouch);
        overflow.setMinimumHeight(minTouch);
        topBar.addView(overflow, new LinearLayout.LayoutParams(minTouch, minTouch));

        topBarView = topBar;
        root.addView(topBar);

        topDividerView = new View(ui.settingsActivity());
        topDividerView.setBackgroundColor(StillwaterUi.LINE);
        root.addView(topDividerView, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1))));

        status = ui.label(ui.getString(R.string.starting), 13, inkMuted);
        status.setId(R.id.chat_status);
        status.setPadding(0, ui.dp(4), 0, ui.dp(4));
        status.setMaxLines(2);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> {
            CharSequence full = status.getText();
            if (full == null || full.length() < 60) return;
            StillwaterUi.showDialog(new android.app.AlertDialog.Builder(ui.settingsActivity())
                    .setTitle(R.string.status_details_title)
                    .setMessage(full)
                    .setPositiveButton(android.R.string.ok, null));
        });
        root.addView(status);
    }

    void mountViewport(LinearLayout root, int surface) {
        LinearLayout chatRoot = new LinearLayout(ui.settingsActivity());
        chatRoot.setOrientation(LinearLayout.VERTICAL);
        ChatPanelDeps panel = deps.chatPanel;
        chatPanel = new ChatPanel(ui.settingsActivity(), panel.send, panel.stopActiveWork,
                panel.onNewChat, panel.onRetryTurn, panel.requestCompact, deps.openSettings,
                panel.openAttachment, panel.clearActiveDocuments, panel.clearActiveImages);
        chatRoot.addView(chatPanel, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout mediaHost = new LinearLayout(ui.settingsActivity());
        mediaHost.setOrientation(LinearLayout.VERTICAL);
        mediaHost.setBackgroundColor(surface);
        mediaHost.setPadding(0, ui.dp(8), 0, ui.dp(4));
        photoButton = mediaButton(ui.getString(R.string.photo), R.drawable.ic_still_photo);
        photoButton.setId(R.id.chat_photo);
        cameraButton = mediaButton(ui.getString(R.string.camera), R.drawable.ic_still_camera);
        cameraButton.setId(R.id.chat_camera);
        documentButton = mediaButton(ui.getString(R.string.attach_document), R.drawable.ic_still_document);
        documentButton.setId(R.id.chat_document);
        documentButton.setContentDescription(ui.getString(R.string.attach_document_talkback));
        recordButton = mediaButton(ui.getString(R.string.voice_input), R.drawable.ic_still_mic);
        recordButton.setId(R.id.voice_record);
        speakButton = mediaButton(ui.getString(R.string.speak_reply), R.drawable.ic_still_speak);
        speakButton.setId(R.id.voice_speak);
        // Structured 3 + 2 tactile dock: generous thumb targets, aligned 22dp icons, zero squishing.
        StillwaterReadable.addFlexibleButtonRow(mediaHost, ui.settingsActivity(), 48,
                new float[]{1f, 1f, 1.15f}, photoButton, cameraButton, documentButton);
        LinearLayout voiceRow = StillwaterReadable.addFlexibleButtonRow(mediaHost, ui.settingsActivity(), 48,
                new float[]{1f, 1f}, recordButton, speakButton);
        LinearLayout.LayoutParams voiceParams = (LinearLayout.LayoutParams) voiceRow.getLayoutParams();
        voiceParams.topMargin = ui.dp(8);
        voiceRow.setLayoutParams(voiceParams);
        chatRoot.addView(mediaHost);
        chatShell = chatRoot;
        root.addView(chatShell, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    void setChatStatus(CharSequence message) {
        if (status == null) return;
        CharSequence cleaned = cleanStatusMessage(message);
        status.setText(cleaned);
        status.setTextColor(statusColorFor(cleaned));
    }

    /** Matches ModelRuntime chat updates: hide status line while a turn status is on the panel. */
    void applyChatStatusDuringTurn(CharSequence message, boolean turnRunning) {
        if (status == null) return;
        CharSequence cleaned = cleanStatusMessage(message);
        status.setText(cleaned);
        status.setTextColor(statusColorFor(cleaned));
        status.setVisibility(turnRunning ? View.GONE : View.VISIBLE);
    }

    private static CharSequence cleanStatusMessage(CharSequence message) {
        if (message == null) return "";
        String raw = message.toString();
        return raw.replace("java.io.IOException: ", "")
                .replace("java.lang.IllegalArgumentException: ", "")
                .replace("java.lang.RuntimeException: ", "");
    }

    private static int statusColorFor(CharSequence message) {
        if (message == null || message.length() == 0) return StillwaterUi.MUTED;
        String lower = message.toString().toLowerCase(Locale.ROOT);
        if (lower.startsWith("model error") || lower.startsWith("import failed")
                || lower.contains("failed") || lower.contains("could not")
                || lower.contains("must be") || lower.contains("exceeds")
                || lower.contains("not enough storage") || lower.contains("błąd")
                || lower.contains("nie udało")) {
            return StillwaterUi.DANGER;
        }
        return StillwaterUi.MUTED;
    }

    CharSequence chatStatusText() {
        return status == null ? "" : status.getText();
    }

    void paintModePill(boolean searchEnabled) {
        if (modePill == null) return;
        modePill.setText(ui.getString(R.string.mode_web_search_short));
        modePill.setVisibility(searchEnabled ? View.VISIBLE : View.GONE);
    }

    void setBrandTitle(CharSequence title) {
        if (brandTitle != null) brandTitle.setText(title);
    }

    void hideChatUiWhenSettingsOpen(boolean settingsOpen) {
        if (topBarView != null) topBarView.setVisibility(settingsOpen ? View.GONE : View.VISIBLE);
        if (topDividerView != null) topDividerView.setVisibility(settingsOpen ? View.GONE : View.VISIBLE);
        if (chatShell != null) chatShell.setVisibility(settingsOpen ? View.GONE : View.VISIBLE);
    }

    ChatPanel chatPanel() {
        return chatPanel;
    }

    View chatShell() {
        return chatShell;
    }

    Button photoButton() {
        return photoButton;
    }

    Button cameraButton() {
        return cameraButton;
    }

    Button documentButton() {
        return documentButton;
    }

    Button recordButton() {
        return recordButton;
    }

    Button speakButton() {
        return speakButton;
    }

    private Button mediaButton(String title, int icon) {
        Button button = ui.button(title);
        StillwaterUi.outlined(button);
        StillwaterReadable.setTextSizeSp(button, 13.5f);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setPadding(ui.dp(12), ui.dp(10), ui.dp(10), ui.dp(10));
        button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(ui.dp(8));
        StillwaterUi.tintStatefulIcon(button);
        int floor = Math.max(ui.dp(48), InterfaceSize.minTouchPx(ui.settingsActivity()));
        button.setMinimumHeight(floor);
        StillwaterReadable.prepareWrappingControl(button, floor);
        return button;
    }
}
