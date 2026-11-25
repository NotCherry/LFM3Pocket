package com.notcherry.lfm3pocket;

import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Settings scroll section for SearXNG endpoint and web-search toggle. */
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

        Deps(Supplier<String> endpoint, BooleanSupplier enabled, SearchPersist persist,
                Consumer<Boolean> setChatIdleMode, Runnable updateControls, BooleanSupplier activityBusy,
                Runnable openSearchSettings, Consumer<CharSequence> chatStatus) {
            this.endpoint = endpoint;
            this.enabled = enabled;
            this.persist = persist;
            this.setChatIdleMode = setChatIdleMode;
            this.updateControls = updateControls;
            this.activityBusy = activityBusy;
            this.openSearchSettings = openSearchSettings;
            this.chatStatus = chatStatus;
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

    SearchSettingsSection(MainActivitySettingsHost host, Deps deps) {
        this.host = host;
        this.deps = deps;
    }

    void mount(LinearLayout configuration, int ink, int inkMuted) {
        LinearLayout card = StillwaterUi.settingsCard(configuration,
                host.getString(R.string.searxng_heading));

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
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-1, -2);
        checkParams.topMargin = host.dp(10);
        card.addView(searchEnabledBox, checkParams);

        searchButton = host.button(host.getString(R.string.web_search_off));
        searchButton.setId(R.id.web_search);
        StillwaterUi.outlined(searchButton);
        searchButton.setOnClickListener(v -> deps.openSearchSettings.run());
        StillwaterReadable.addFlexibleFullWidth(card, searchButton, 48);
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
        searchEndpointField.setText(deps.endpoint.get());
        searchEnabledBox.setChecked(deps.enabled.getAsBoolean());
    }

    void focusEndpointField() {
        if (searchEndpointField != null) searchEndpointField.requestFocus();
    }

    void saveInline() {
        if (searchEndpointField == null) return;
        String value = searchEndpointField.getText().toString().trim();
        if (!SearchEndpointPolicy.accepts(value)) {
            deps.chatStatus.accept("Use an HTTPS SearXNG search endpoint.");
            return;
        }
        boolean enabled = SearchEndpointPolicy.enabled(searchEnabledBox.isChecked(), value);
        deps.persist.save(value, enabled);
        deps.setChatIdleMode.accept(enabled);
        deps.updateControls.run();
    }

    void paintWebSearchControl(boolean busy) {
        if (searchButton == null) return;
        searchButton.setText(deps.enabled.getAsBoolean()
                ? host.getString(R.string.web_search_on)
                : host.getString(R.string.web_search_off));
        searchButton.setEnabled(!busy);
    }
}
