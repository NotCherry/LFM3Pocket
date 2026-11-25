package com.notcherry.lfm3pocket;

import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings scroll section for GGUF import/open controls and {@link ModelManagerPanel}. */
final class ModelImportSettingsSection {
    static final class Deps {
        final Runnable pickModel;
        final Runnable pickMmproj;
        final Runnable openModelUri;
        final Runnable openProjectorUri;
        final Runnable reloadSelectedModel;
        final Runnable unloadSelectedModel;
        final Runnable showInferenceSettings;

        Deps(Runnable pickModel, Runnable pickMmproj, Runnable openModelUri, Runnable openProjectorUri,
                Runnable reloadSelectedModel, Runnable unloadSelectedModel, Runnable showInferenceSettings) {
            this.pickModel = pickModel;
            this.pickMmproj = pickMmproj;
            this.openModelUri = openModelUri;
            this.openProjectorUri = openProjectorUri;
            this.reloadSelectedModel = reloadSelectedModel;
            this.unloadSelectedModel = unloadSelectedModel;
            this.showInferenceSettings = showInferenceSettings;
        }
    }

    private final SettingsUiFactory ui;
    private final Deps deps;
    private Button modelButton;
    private Button mmprojButton;
    private Button openModelUriButton;
    private Button openProjectorUriButton;
    private ModelManagerPanel modelManager;

    ModelImportSettingsSection(SettingsUiFactory ui, Deps deps) {
        this.ui = ui;
        this.deps = deps;
    }

    void mount(LinearLayout configuration, int accent) {
        LinearLayout card = StillwaterUi.settingsCard(configuration, ui.getString(R.string.model_heading));
        StillwaterUi.settingsHelp(card, ui.getString(R.string.model_import_help));

        modelButton = ui.button(ui.getString(R.string.import_model));
        modelButton.setId(R.id.import_model);
        StillwaterUi.accentOutlined(modelButton);
        modelButton.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        modelButton.setOnClickListener(v -> deps.pickModel.run());

        mmprojButton = ui.button(ui.getString(R.string.import_projector));
        mmprojButton.setId(R.id.import_projector);
        StillwaterUi.accentOutlined(mmprojButton);
        mmprojButton.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        mmprojButton.setOnClickListener(v -> deps.pickMmproj.run());
        StillwaterReadable.addFlexibleButtonRow(card, ui.settingsActivity(), 50,
                modelButton, mmprojButton);

        openModelUriButton = ui.button(ui.getString(R.string.open_model_from_storage));
        openModelUriButton.setId(R.id.open_model_uri);
        StillwaterUi.outlined(openModelUriButton);
        openModelUriButton.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        openModelUriButton.setOnClickListener(v -> deps.openModelUri.run());

        openProjectorUriButton = ui.button(ui.getString(R.string.open_projector_from_storage));
        openProjectorUriButton.setId(R.id.open_projector_uri);
        StillwaterUi.outlined(openProjectorUriButton);
        openProjectorUriButton.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        openProjectorUriButton.setOnClickListener(v -> deps.openProjectorUri.run());
        StillwaterReadable.addFlexibleButtonRow(card, ui.settingsActivity(), 50,
                openModelUriButton, openProjectorUriButton);

        StillwaterUi.settingsDivider(card);

        // Model manager stays near the top so Unload/Load remain on-screen for Stage 1.
        // Hosted inside Settings, so suppress the redundant "Settings" jump button.
        modelManager = new ModelManagerPanel(ui.settingsActivity(),
                deps.reloadSelectedModel, deps.unloadSelectedModel, deps.showInferenceSettings, false);
        card.addView(modelManager, new LinearLayout.LayoutParams(-1, -2));
    }

    void paintImportButtons(boolean busy) {
        if (modelButton != null) modelButton.setEnabled(!busy);
        if (mmprojButton != null) mmprojButton.setEnabled(!busy);
        if (openModelUriButton != null) openModelUriButton.setEnabled(!busy);
        if (openProjectorUriButton != null) openProjectorUriButton.setEnabled(!busy);
    }

    void paintModelManager(String inferenceSummary, String modelLabel, String projectorLabel, boolean busy,
            boolean modelLoaded, boolean selectionFailed) {
        if (modelManager == null) return;
        modelManager.showSettings(inferenceSummary);
        modelManager.updateSelection(modelLabel, projectorLabel, busy, modelLoaded, selectionFailed);
    }
}
