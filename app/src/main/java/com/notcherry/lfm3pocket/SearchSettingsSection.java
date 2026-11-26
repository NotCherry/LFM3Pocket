package com.notcherry.lfm3pocket;

import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Settings scroll section for Web Search provider selection, credentials/endpoint, live test probe, and toggle. */
final class SearchSettingsSection {
    static final class Deps {
        final Supplier<SearchConfig> config;
        final Function<SearchProvider, String> savedApiKeyFor;
        final Consumer<SearchConfig> persist;
        final Consumer<Boolean> setChatIdleMode;
        final Runnable updateControls;
        final BooleanSupplier activityBusy;
        final Runnable openSearchSettings;
        final Consumer<CharSequence> chatStatus;
        final ExecutorService worker;
        final Consumer<Runnable> runOnUiThread;

        Deps(Supplier<SearchConfig> config,
             Function<SearchProvider, String> savedApiKeyFor,
             Consumer<SearchConfig> persist,
             Consumer<Boolean> setChatIdleMode,
             Runnable updateControls,
             BooleanSupplier activityBusy,
             Runnable openSearchSettings,
             Consumer<CharSequence> chatStatus,
             ExecutorService worker,
             Consumer<Runnable> runOnUiThread) {
            this.config = config;
            this.savedApiKeyFor = savedApiKeyFor;
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

    private final MainActivitySettingsHost host;
    private final Deps deps;
    private final Map<SearchProvider, Button> providerButtons = new EnumMap<>(SearchProvider.class);
    private final Map<SearchProvider, String> draftApiKeys = new EnumMap<>(SearchProvider.class);
    private SearchProvider selectedProvider = SearchProvider.SEARXNG;
    private TextView providerHelp;
    private EditText searchEndpointField;
    private EditText searchApiKeyField;
    private CheckBox searchEnabledBox;
    private Button testButton;
    private TextView testStatus;
    private boolean bindingFields;
    private boolean testing;

    SearchSettingsSection(MainActivitySettingsHost host, Deps deps) {
        this.host = host;
        this.deps = deps;
    }

    void mount(LinearLayout configuration, int ink, int inkMuted) {
        SearchConfig initial = currentConfig();
        selectedProvider = initial.provider;

        LinearLayout card = StillwaterUi.settingsCard(configuration,
                host.getString(R.string.search_heading));

        Button searxBtn = providerChoice(SearchProvider.SEARXNG, R.string.search_provider_searxng,
                R.id.search_provider_searxng);
        Button wikiBtn = providerChoice(SearchProvider.WIKIPEDIA, R.string.search_provider_wikipedia,
                R.id.search_provider_wikipedia);
        StillwaterReadable.addFlexibleButtonRow(card, host.settingsActivity(), 44, searxBtn, wikiBtn);

        Button braveBtn = providerChoice(SearchProvider.BRAVE, R.string.search_provider_brave,
                R.id.search_provider_brave);
        Button tavilyBtn = providerChoice(SearchProvider.TAVILY, R.string.search_provider_tavily,
                R.id.search_provider_tavily);
        StillwaterReadable.addFlexibleButtonRow(card, host.settingsActivity(), 44, braveBtn, tavilyBtn);

        providerHelp = host.label(helpTextFor(selectedProvider), 13, inkMuted);
        providerHelp.setPadding(0, host.dp(8), 0, host.dp(10));
        StillwaterReadable.prepareWrappingLabel(providerHelp);
        card.addView(providerHelp);

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
        searchEndpointField.setText(initial.endpoint);
        card.addView(searchEndpointField, new LinearLayout.LayoutParams(-1, -2));

        searchApiKeyField = new EditText(host.settingsActivity());
        searchApiKeyField.setId(R.id.search_api_key);
        searchApiKeyField.setSingleLine(true);
        searchApiKeyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        StillwaterReadable.setTextSizeSp(searchApiKeyField, 14.5f);
        searchApiKeyField.setTypeface(StillwaterUi.mono(host.settingsActivity()));
        StillwaterUi.styleBoxedInput(searchApiKeyField);
        searchApiKeyField.setMinimumHeight(Math.max(host.dp(48), InterfaceSize.minTouchPx(host.settingsActivity())));
        searchApiKeyField.setHintTextColor(StillwaterUi.DISABLED);
        searchApiKeyField.setText(initial.apiKey);
        LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(-1, -2);
        keyParams.topMargin = host.dp(8);
        card.addView(searchApiKeyField, keyParams);

        searchEnabledBox = new CheckBox(host.settingsActivity());
        searchEnabledBox.setId(R.id.search_enabled);
        searchEnabledBox.setText(R.string.use_web_search);
        StillwaterUi.styleCheck(searchEnabledBox);
        searchEnabledBox.setChecked(initial.enabled);
        searchEnabledBox.setOnCheckedChangeListener((button, checked) -> {
            if (bindingFields) return;
            applyCheckboxToggle(checked);
        });
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-1, -2);
        checkParams.topMargin = host.dp(10);
        card.addView(searchEnabledBox, checkParams);

        testButton = host.button(host.getString(R.string.search_test_button));
        testButton.setId(R.id.search_test);
        StillwaterUi.outlined(testButton);
        testButton.setOnClickListener(v -> runEndpointTest());
        StillwaterReadable.addFlexibleFullWidth(card, testButton, 48);

        testStatus = host.label("", 13, inkMuted);
        testStatus.setId(R.id.search_test_status);
        testStatus.setPadding(0, host.dp(6), 0, 0);
        StillwaterReadable.prepareWrappingLabel(testStatus);
        card.addView(testStatus);

        paintProviderUi();
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
        SearchConfig current = currentConfig();
        bindingFields = true;
        selectedProvider = current.provider;
        draftApiKeys.clear();
        for (SearchProvider p : SearchProvider.values()) {
            if (p.needsApiKey || p.supportsOptionalToken) {
                String saved = deps.savedApiKeyFor == null ? "" : deps.savedApiKeyFor.apply(p);
                draftApiKeys.put(p, saved == null ? "" : saved.trim());
            }
        }
        draftApiKeys.put(selectedProvider, current.apiKey);
        searchEndpointField.setText(current.endpoint);
        if (searchApiKeyField != null) searchApiKeyField.setText(current.apiKey);
        searchEnabledBox.setChecked(current.enabled);
        paintProviderUi();
        bindingFields = false;
    }

    private void syncEnabledCheckbox(boolean enabled) {
        if (searchEnabledBox == null) return;
        bindingFields = true;
        searchEnabledBox.setChecked(enabled);
        bindingFields = false;
    }

    void focusEndpointField() {
        if (selectedProvider == SearchProvider.SEARXNG && searchEndpointField != null) {
            searchEndpointField.requestFocus();
        } else if (selectedProvider.needsApiKey && searchApiKeyField != null) {
            searchApiKeyField.requestFocus();
        }
    }

    void saveInline() {
        if (searchEndpointField == null) return;
        String rawEndpoint = readEndpoint();
        String rawApiKey = readApiKey();
        if (selectedProvider == SearchProvider.SEARXNG && !SearchEndpointPolicy.accepts(rawEndpoint)) {
            syncEnabledCheckbox(currentConfig().enabled);
            deps.chatStatus.accept(SearchEndpointPolicy.SEARXNG_ENDPOINT_ERROR);
            return;
        }
        String normalizedEndpoint = selectedProvider == SearchProvider.SEARXNG
                ? WebSearch.normalizeEndpoint(rawEndpoint) : rawEndpoint;
        if (selectedProvider == SearchProvider.SEARXNG && !normalizedEndpoint.equals(rawEndpoint)) {
            searchEndpointField.setText(normalizedEndpoint);
        }
        boolean requested = searchEnabledBox.isChecked();
        String validationError = SearchEndpointPolicy.validationError(selectedProvider, normalizedEndpoint, rawApiKey);
        boolean enabled = requested && validationError == null;
        if (requested && !enabled) {
            syncEnabledCheckbox(false);
            deps.chatStatus.accept(validationError);
        }
        draftApiKeys.put(selectedProvider, rawApiKey);
        SearchConfig next = new SearchConfig(selectedProvider, normalizedEndpoint, rawApiKey, enabled);
        deps.persist.accept(next);
        deps.setChatIdleMode.accept(enabled);
        deps.updateControls.run();
        if (!requested || enabled) {
            deps.chatStatus.accept(host.getString(R.string.search_saved, selectedProvider.displayName));
        }
    }

    private Button providerChoice(SearchProvider provider, int labelRes, int viewId) {
        Button button = host.button(host.getString(labelRes));
        button.setId(viewId);
        button.setOnClickListener(v -> selectProvider(provider));
        providerButtons.put(provider, button);
        return button;
    }

    private void selectProvider(SearchProvider nextProvider) {
        if (nextProvider == null || nextProvider == selectedProvider) return;
        if (searchApiKeyField != null && (selectedProvider.needsApiKey || selectedProvider.supportsOptionalToken)) {
            draftApiKeys.put(selectedProvider, readApiKey());
        }
        selectedProvider = nextProvider;
        bindingFields = true;
        if (searchApiKeyField != null) {
            String restoredKey = draftApiKeys.get(selectedProvider);
            if (restoredKey == null && deps.savedApiKeyFor != null) {
                restoredKey = deps.savedApiKeyFor.apply(selectedProvider);
            }
            searchApiKeyField.setText(restoredKey == null ? "" : restoredKey);
        }
        paintProviderUi();
        if (testStatus != null) testStatus.setText("");
        bindingFields = false;

        // If web search is currently enabled, keep persisted state consistent when switching providers.
        SearchConfig current = currentConfig();
        String rawEndpoint = readEndpoint();
        String rawApiKey = readApiKey();
        String normalizedEndpoint = selectedProvider == SearchProvider.SEARXNG && SearchEndpointPolicy.accepts(rawEndpoint)
                ? WebSearch.normalizeEndpoint(rawEndpoint) : current.endpoint;
        boolean canStayEnabled = current.enabled
                && SearchEndpointPolicy.configured(selectedProvider, normalizedEndpoint, rawApiKey);
        if (current.enabled && !canStayEnabled) {
            bindingFields = true;
            searchEnabledBox.setChecked(false);
            bindingFields = false;
        }
        SearchConfig updated = new SearchConfig(selectedProvider, normalizedEndpoint, rawApiKey, canStayEnabled);
        deps.persist.accept(updated);
        deps.setChatIdleMode.accept(canStayEnabled);
        deps.updateControls.run();
    }

    private void paintProviderUi() {
        for (Map.Entry<SearchProvider, Button> entry : providerButtons.entrySet()) {
            StillwaterUi.segmented(entry.getValue(), entry.getKey() == selectedProvider);
        }
        if (providerHelp != null) {
            providerHelp.setText(helpTextFor(selectedProvider));
        }
        if (searchEndpointField != null) {
            searchEndpointField.setVisibility(selectedProvider.needsEndpoint ? View.VISIBLE : View.GONE);
        }
        if (searchApiKeyField != null) {
            boolean showKey = selectedProvider.needsApiKey || selectedProvider.supportsOptionalToken;
            searchApiKeyField.setVisibility(showKey ? View.VISIBLE : View.GONE);
            if (selectedProvider == SearchProvider.BRAVE) {
                searchApiKeyField.setHint(R.string.search_key_hint_brave);
            } else if (selectedProvider == SearchProvider.TAVILY) {
                searchApiKeyField.setHint(R.string.search_key_hint_tavily);
            } else if (selectedProvider == SearchProvider.SEARXNG) {
                searchApiKeyField.setHint(R.string.search_token_hint_searxng);
            }
        }
    }

    private String helpTextFor(SearchProvider provider) {
        switch (provider) {
            case WIKIPEDIA:
                return host.getString(R.string.search_help_wikipedia);
            case BRAVE:
                return host.getString(R.string.search_help_brave);
            case TAVILY:
                return host.getString(R.string.search_help_tavily);
            case SEARXNG:
            default:
                return host.getString(R.string.search_help_searxng);
        }
    }

    private void applyCheckboxToggle(boolean checked) {
        if (searchEndpointField == null) return;
        String rawEndpoint = readEndpoint();
        String rawApiKey = readApiKey();
        if (!checked) {
            String persistedEndpoint = selectedProvider == SearchProvider.SEARXNG && SearchEndpointPolicy.accepts(rawEndpoint)
                    ? WebSearch.normalizeEndpoint(rawEndpoint) : currentConfig().endpoint;
            draftApiKeys.put(selectedProvider, rawApiKey);
            deps.persist.accept(new SearchConfig(selectedProvider, persistedEndpoint, rawApiKey, false));
            deps.setChatIdleMode.accept(false);
            deps.updateControls.run();
            return;
        }
        String normalizedEndpoint = selectedProvider == SearchProvider.SEARXNG
                ? WebSearch.normalizeEndpoint(rawEndpoint) : rawEndpoint;
        String validationError = SearchEndpointPolicy.validationError(selectedProvider, normalizedEndpoint, rawApiKey);
        if (validationError != null) {
            syncEnabledCheckbox(false);
            deps.chatStatus.accept(validationError);
            deps.updateControls.run();
            return;
        }
        if (selectedProvider == SearchProvider.SEARXNG && !normalizedEndpoint.equals(rawEndpoint)) {
            searchEndpointField.setText(normalizedEndpoint);
        }
        draftApiKeys.put(selectedProvider, rawApiKey);
        deps.persist.accept(new SearchConfig(selectedProvider, normalizedEndpoint, rawApiKey, true));
        deps.setChatIdleMode.accept(true);
        deps.updateControls.run();
    }

    private void runEndpointTest() {
        if (testing || searchEndpointField == null) return;
        String rawEndpoint = readEndpoint();
        String rawApiKey = readApiKey();
        String normalizedEndpoint = selectedProvider == SearchProvider.SEARXNG
                ? WebSearch.normalizeEndpoint(rawEndpoint) : rawEndpoint;
        String validationError = SearchEndpointPolicy.validationError(selectedProvider, normalizedEndpoint, rawApiKey);
        if (validationError != null) {
            deps.chatStatus.accept(validationError);
            if (testStatus != null) {
                testStatus.setText(validationError);
                testStatus.setTextColor(StillwaterUi.DANGER);
            }
            return;
        }
        if (selectedProvider == SearchProvider.SEARXNG && !normalizedEndpoint.equals(rawEndpoint)) {
            searchEndpointField.setText(normalizedEndpoint);
        }
        final SearchConfig testConfig = new SearchConfig(selectedProvider, normalizedEndpoint, rawApiKey, true);
        testing = true;
        if (testButton != null) testButton.setEnabled(false);
        if (testStatus != null) {
            testStatus.setText(host.getString(R.string.search_testing_provider, selectedProvider.displayName));
            testStatus.setTextColor(StillwaterUi.MUTED);
        }
        long startedMs = System.currentTimeMillis();
        deps.worker.execute(() -> {
            String error = null;
            int count = 0;
            try {
                List<WebSearch.Source> sources = WebSearch.search(testConfig, "test", "");
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
        if (searchEnabledBox != null) {
            searchEnabledBox.setEnabled(!busy);
        }
        if (testButton != null) {
            testButton.setEnabled(!busy && !testing);
        }
    }

    private SearchConfig currentConfig() {
        SearchConfig cfg = deps.config == null ? null : deps.config.get();
        return cfg == null ? new SearchConfig(SearchProvider.SEARXNG, "", "", false) : cfg;
    }

    private String readEndpoint() {
        return searchEndpointField == null || searchEndpointField.getText() == null
                ? "" : searchEndpointField.getText().toString().trim();
    }

    private String readApiKey() {
        return searchApiKeyField == null || searchApiKeyField.getText() == null
                ? "" : searchApiKeyField.getText().toString().trim();
    }
}
