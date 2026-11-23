package com.notcherry.lfm3pocket;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

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

        ChatPanelDeps(Consumer<String> send, Runnable stopActiveWork, Runnable onNewChat,
                Consumer<String> onRetryTurn, Runnable requestCompact) {
            this.send = send;
            this.stopActiveWork = stopActiveWork;
            this.onNewChat = onNewChat;
            this.onRetryTurn = onRetryTurn;
            this.requestCompact = requestCompact;
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
    private TextView brandTitle;
    private TextView modePill;
    private Button settingsOpenButton;
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
        LinearLayout topBar = new LinearLayout(ui.settingsActivity());
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(ui.settingsActivity());
        titles.setOrientation(LinearLayout.VERTICAL);
        brandTitle = ui.label(deps.historyTitle.get(), 17, ink);
        brandTitle.setTypeface(StillwaterUi.serif(ui.settingsActivity()));
        StillwaterReadable.prepareWrappingLabel(brandTitle);
        titles.addView(brandTitle);
        modePill = ui.label(deps.searchEnabled.getAsBoolean()
                        ? ui.getString(R.string.mode_web_search)
                        : ui.getString(R.string.mode_local),
                11, inkMuted);
        modePill.setTypeface(StillwaterUi.sans(ui.settingsActivity()));
        StillwaterReadable.prepareWrappingLabel(modePill);
        titles.addView(modePill);
        topBar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        Button newChat = ui.button(ui.getString(R.string.new_chat));
        newChat.setId(R.id.chat_new_chat);
        StillwaterUi.quiet(newChat);
        newChat.setTextColor(accent);
        newChat.setOnClickListener(v -> deps.requestNewChat.run());
        newChat.setMinimumWidth(InterfaceSize.minTouchPx(ui.settingsActivity()));
        newChat.setMinimumHeight(InterfaceSize.minTouchPx(ui.settingsActivity()));
        StillwaterReadable.prepareWrappingControl(newChat, ui.dp(44));
        topBar.addView(newChat, new LinearLayout.LayoutParams(-2, -2));
        settingsOpenButton = ui.button("");
        settingsOpenButton.setId(R.id.settings_open);
        settingsOpenButton.setContentDescription(ui.getString(R.string.settings_title));
        settingsOpenButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_still_gear, 0, 0, 0);
        StillwaterUi.quiet(settingsOpenButton);
        settingsOpenButton.setOnClickListener(v -> deps.openSettings.run());
        LinearLayout.LayoutParams settingsIconParams = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36));
        settingsOpenButton.setMinimumWidth(InterfaceSize.minTouchPx(ui.settingsActivity()));
        settingsOpenButton.setMinimumHeight(InterfaceSize.minTouchPx(ui.settingsActivity()));
        topBar.addView(settingsOpenButton, settingsIconParams);
        Button overflow = ui.button("⋮");
        overflow.setId(R.id.chat_overflow);
        StillwaterUi.quiet(overflow);
        overflow.setTextColor(inkMuted);
        overflow.setOnClickListener(v -> {
            android.view.ContextThemeWrapper menuContext =
                    new android.view.ContextThemeWrapper(ui.settingsActivity(), R.style.Theme_Stillwater);
            android.widget.PopupMenu menu = new android.widget.PopupMenu(menuContext, v);
            menu.getMenu().add(0, 1, 0, ui.getString(R.string.settings_title));
            menu.getMenu().add(0, 2, 1, "Web search");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) deps.openSettings.run();
                else if (item.getItemId() == 2) deps.showSearchSettings.run();
                return true;
            });
            menu.show();
        });
        overflow.setMinimumWidth(InterfaceSize.minTouchPx(ui.settingsActivity()));
        overflow.setMinimumHeight(InterfaceSize.minTouchPx(ui.settingsActivity()));
        topBar.addView(overflow, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        topBarView = topBar;
        root.addView(topBar);

        status = ui.label(ui.getString(R.string.starting), 12, inkMuted);
        status.setId(R.id.chat_status);
        status.setPadding(0, ui.dp(4), 0, ui.dp(4));
        StillwaterReadable.prepareWrappingLabel(status);
        root.addView(status);
    }

    void mountViewport(LinearLayout root, int surface) {
        LinearLayout chatRoot = new LinearLayout(ui.settingsActivity());
        chatRoot.setOrientation(LinearLayout.VERTICAL);
        ChatPanelDeps panel = deps.chatPanel;
        chatPanel = new ChatPanel(ui.settingsActivity(), panel.send, panel.stopActiveWork,
                panel.onNewChat, panel.onRetryTurn, panel.requestCompact);
        chatRoot.addView(chatPanel, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout mediaHost = new LinearLayout(ui.settingsActivity());
        mediaHost.setOrientation(LinearLayout.VERTICAL);
        mediaHost.setBackgroundColor(surface);
        mediaHost.setPadding(0, ui.dp(2), 0, ui.dp(2));
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
        // Two rows when stacked (narrow / large fonts); one row otherwise.
        if (StillwaterReadable.shouldStack(ui.settingsActivity())) {
            StillwaterReadable.addFlexibleButtonRow(mediaHost, ui.settingsActivity(), 46,
                    photoButton, cameraButton, documentButton);
            StillwaterReadable.addFlexibleButtonRow(mediaHost, ui.settingsActivity(), 46,
                    new float[]{1f, 1.2f}, recordButton, speakButton);
        } else {
            StillwaterReadable.addFlexibleButtonRow(mediaHost, ui.settingsActivity(), 46,
                    new float[]{1f, 1f, 1f, 1.2f, 1.2f},
                    photoButton, cameraButton, documentButton, recordButton, speakButton);
        }
        chatRoot.addView(mediaHost);
        chatShell = chatRoot;
        root.addView(chatShell, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    void setChatStatus(CharSequence message) {
        if (status != null) status.setText(message);
    }

    /** Matches ModelRuntime chat updates: hide status line while a turn status is on the panel. */
    void applyChatStatusDuringTurn(CharSequence message, boolean turnRunning) {
        if (status == null) return;
        status.setText(message);
        status.setVisibility(turnRunning ? View.GONE : View.VISIBLE);
    }

    CharSequence chatStatusText() {
        return status == null ? "" : status.getText();
    }

    void paintModePill(boolean searchEnabled) {
        if (modePill == null) return;
        modePill.setText(searchEnabled
                ? ui.getString(R.string.mode_web_search)
                : ui.getString(R.string.mode_local));
    }

    void setBrandTitle(CharSequence title) {
        if (brandTitle != null) brandTitle.setText(title);
    }

    void hideChatUiWhenSettingsOpen(boolean settingsOpen) {
        if (settingsOpenButton != null) {
            settingsOpenButton.setVisibility(settingsOpen ? View.GONE : View.VISIBLE);
        }
        if (topBarView != null) topBarView.setVisibility(settingsOpen ? View.GONE : View.VISIBLE);
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

    TextView statusView() {
        return status;
    }

    private Button mediaButton(String title, int icon) {
        Button button = ui.button(title);
        StillwaterReadable.setTextSizeSp(button, 12);
        button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(ui.dp(4));
        StillwaterUi.quiet(button);
        StillwaterReadable.prepareWrappingControl(button, ui.dp(46));
        return button;
    }
}
