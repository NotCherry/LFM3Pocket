package com.notcherry.lfm3pocket;

import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Settings scroll section for SearXNG endpoint, live test probe, and web-search toggle. */
final class SearchSettingsSection {
    static final class Deps {
        final Supplier<String> endpoint;
        final BooleanSupplier enabled;
        final SearchPersist persist;
        final Consumer<Boolean> setChatIdleMode;
        final Runnable updateControls;
        final BooleanSupplier activityBusy;
        final Runnable openSearchSettings;
        final Consumer<CharSequence> chatStatus;
        final ExecutorService worker;
        final Consumer<Runnable> runOnUiThread;

        Deps(Supplier<String> endpoint, BooleanSupplier enabled, SearchPersist persist,
                Consumer<Boolean> setChatIdleMode, Runnable updateControls, BooleanSupplier activityBusy,
                Runnable openSearchSettings, Consumer<CharSequence> chatStatus,
                ExecutorService worker, Consumer<Runnable> runOnUiThread) {
            this.endpoint = endpoint;
            this.enabled = enabled;
            this.persist = persist;
            this.setChatIdleMode = setChatIdleMode;
            this.updateControls = updateControls;
            this.activityBusy = activityBusy;
            this.openSearchSettings = openSearchSettings;
            this.chatStatus = chatStatus;
            this.worker = worker;
            this.runOnUiThread = runOnUiThread;
        }
    }

    interface SearchPersist {
        void save(String endpoint, boolean enabled);
    }

    private final MainActivitySettingsHost host;
    private final Deps deps;
    private EditText searchEndpointField;
    private CheckBox searchEnabledBox;
    private Button searchButton;
    private Button testButton;
    private TextView testStatus;
    private boolean bindingFields;
    private boolean testing;

    SearchSettingsSection(MainActivitySettingsHost host, Deps deps) {
        this.host = host;
        this.deps = deps;
    }

    void mount(LinearLayout configuration, int ink, int inkMuted) {
        LinearLayout card = StillwaterUi.settingsCard(configuration,
                host.getString(R.string.searxng_heading));
        StillwaterUi.settingsHelp(card, host.getString(R.string.search_help));

        searchEndpointField = new EditText(host.settingsActivity());
        searchEndpointField.setId(R.id.search_endpoint);
        searchEndpointField.setSingleLine(true);
        searchEndpointField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        searchEndpointField.setHint("https://your-searxng.example.org/search");
        StillwaterReadable.setTextSizeSp(searchEndpointField, 14.5f);
        searchEndpointField.setTypeface(StillwaterUi.mono(host.settingsActivity()));
        StillwaterUi.styleBoxedInput(searchEndpointField);
        searchEndpointField.setMinimumHeight(Math.max(host.dp(48), InterfaceSize.minTouchPx(host.settingsActivity())));
        searchEndpointField.setHintTextColor(StillwaterUi.DISABLED);
        searchEndpointField.setText(deps.endpoint.get());
        card.addView(searchEndpointField, new LinearLayout.LayoutParams(-1, -2));

        searchEnabledBox = new CheckBox(host.settingsActivity());
        searchEnabledBox.setId(R.id.search_enabled);
        searchEnabledBox.setText(R.string.use_web_search);
        StillwaterUi.styleCheck(searchEnabledBox);
        searchEnabledBox.setChecked(deps.enabled.getAsBoolean());
        searchEnabledBox.setOnCheckedChangeListener((button, checked) -> {
            if (bindingFields) return;
            applyCheckboxToggle(checked);
        });
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-1, -2);
        checkParams.topMargin = host.dp(10);
        card.addView(searchEnabledBox, checkParams);

        searchButton = host.button(host.getString(R.string.web_search_off));
        searchButton.setId(R.id.web_search);
        StillwaterUi.outlined(searchButton);
        searchButton.setOnClickListener(v -> toggleOrFocusEndpoint());

        testButton = host.button(host.getString(R.string.search_test_button));
        testButton.setId(R.id.search_test);
        StillwaterUi.outlined(testButton);
        testButton.setOnClickListener(v -> runEndpointTest());

        StillwaterReadable.addFlexibleButtonRow(card, host.settingsActivity(), 48,
                searchButton, testButton);

        testStatus = host.label("", 13, inkMuted);
        testStatus.setId(R.id.search_test_status);
        testStatus.setPadding(0, host.dp(6), 0, 0);
        StillwaterReadable.prepareWrappingLabel(testStatus);
        card.addView(testStatus);

        paintWebSearchControl(deps.activityBusy.getAsBoolean());
    }

    Button createSaveButton() {
        Button saveSearch = host.button(host.getString(R.string.save_search));
        saveSearch.setId(R.id.search_save);
        StillwaterUi.outlined(saveSearch);
        saveSearch.setOnClickListener(v -> saveInline());
        return saveSearch;
    }

    void syncFields() {
        if (searchEndpointField == null) return;
        bindingFields = true;
        searchEndpointField.setText(deps.endpoint.get());
        searchEnabledBox.setChecked(deps.enabled.getAsBoolean());
        bindingFields = false;
    }

    void focusEndpointField() {
        if (searchEndpointField != null) searchEndpointField.requestFocus();
    }

    void saveInline() {
        if (searchEndpointField == null) return;
        String raw = searchEndpointField.getText().toString().trim();
        if (!SearchEndpointPolicy.accepts(raw)) {
            deps.chatStatus.accept("Use an HTTPS SearXNG search endpoint.");
            return;
        }
        String normalized = WebSearch.normalizeEndpoint(raw);
        if (!normalized.equals(raw)) {
            searchEndpointField.setText(normalized);
        }
        boolean enabled = SearchEndpointPolicy.enabled(searchEnabledBox.isChecked(), normalized);
        if (searchEnabledBox.isChecked() && !enabled) {
            deps.chatStatus.accept("Use an HTTPS SearXNG search endpoint.");
        }
        deps.persist.save(normalized, enabled);
        deps.setChatIdleMode.accept(enabled);
        deps.updateControls.run();
    }

    private void applyCheckboxToggle(boolean checked) {
        if (searchEndpointField == null) return;
        String raw = searchEndpointField.getText().toString().trim();
        if (!checked) {
            String normalized = SearchEndpointPolicy.accepts(raw) ? WebSearch.normalizeEndpoint(raw) : deps.endpoint.get();
            deps.persist.save(normalized, false);
            deps.setChatIdleMode.accept(false);
            deps.updateControls.run();
            return;
        }
        if (!SearchEndpointPolicy.accepts(raw) || raw.isEmpty()) {
            deps.chatStatus.accept("Use an HTTPS SearXNG search endpoint.");
            return;
        }
        String normalized = WebSearch.normalizeEndpoint(raw);
        if (!normalized.equals(raw)) {
            searchEndpointField.setText(normalized);
        }
        deps.persist.save(normalized, true);
        deps.setChatIdleMode.accept(true);
        deps.updateControls.run();
    }

    private void toggleOrFocusEndpoint() {
        if (searchEndpointField == null) {
            deps.openSearchSettings.run();
            return;
        }
        String raw = searchEndpointField.getText().toString().trim();
        if (!SearchEndpointPolicy.enabled(true, raw)) {
            deps.openSearchSettings.run();
            return;
        }
        boolean next = !deps.enabled.getAsBoolean();
        bindingFields = true;
        searchEnabledBox.setChecked(next);
        bindingFields = false;
        applyCheckboxToggle(next);
    }

    private void runEndpointTest() {
        if (testing || searchEndpointField == null) return;
        String raw = searchEndpointField.getText().toString().trim();
        if (!SearchEndpointPolicy.accepts(raw) || raw.isEmpty()) {
            String msg = "Use an HTTPS SearXNG search endpoint.";
            deps.chatStatus.accept(msg);
            if (testStatus != null) {
                testStatus.setText(msg);
                testStatus.setTextColor(StillwaterUi.DANGER);
            }
            return;
        }
        String normalized = WebSearch.normalizeEndpoint(raw);
        if (!normalized.equals(raw)) searchEndpointField.setText(normalized);
        testing = true;
        if (testButton != null) testButton.setEnabled(false);
        if (testStatus != null) {
            testStatus.setText(host.getString(R.string.search_testing));
            testStatus.setTextColor(StillwaterUi.MUTED);
        }
        long startedMs = System.currentTimeMillis();
        deps.worker.execute(() -> {
            String error = null;
            int count = 0;
            try {
                List<WebSearch.Source> sources = WebSearch.search(normalized, "test");
                count = sources == null ? 0 : sources.size();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            long elapsedMs = Math.max(1L, System.currentTimeMillis() - startedMs);
            final String failure = error;
            final int found = count;
            deps.runOnUiThread.accept(() -> {
                testing = false;
                if (host.isHostFinishing()) return;
                paintWebSearchControl(deps.activityBusy.getAsBoolean());
                if (testStatus == null) return;
                if (failure != null) {
                    String msg = host.getString(R.string.search_test_failed, failure);
                    testStatus.setText(msg);
                    testStatus.setTextColor(StillwaterUi.DANGER);
                    deps.chatStatus.accept(msg);
                } else if (found == 0) {
                    String msg = host.getString(R.string.search_test_empty, elapsedMs);
                    testStatus.setText(msg);
                    testStatus.setTextColor(StillwaterUi.MUTED);
                    deps.chatStatus.accept(msg);
                } else {
                    String msg = host.getString(R.string.search_test_ok, found, elapsedMs);
                    testStatus.setText(msg);
                    testStatus.setTextColor(StillwaterUi.OK);
                    deps.chatStatus.accept(msg);
                }
            });
        });
    }

    void paintWebSearchControl(boolean busy) {
        if (searchButton != null) {
            searchButton.setText(deps.enabled.getAsBoolean()
                    ? host.getString(R.string.web_search_on)
                    : host.getString(R.string.web_search_off));
            searchButton.setEnabled(!busy);
        }
        if (testButton != null) {
            testButton.setEnabled(!busy && !testing);
        }
    }
}
