package com.notcherry.lfm3pocket;

import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Settings scroll section for chat image prepare / resize / JPEG quality. */
final class ImageSettingsSection {
    private final MainActivitySettingsHost host;
    private final ImageSettingsStore store;
    private final Supplier<ImageSettings> currentSettings;
    private final Consumer<ImageSettings> onSettingsSaved;
    private final Consumer<CharSequence> chatStatus;
    private boolean bindingImageSettings;
    private CheckBox imagePrepareBox;
    private CheckBox imageCropBox;
    private EditText imageQualityField;
    private Button imageMax384Button;
    private Button imageMax512Button;
    private Button imageMax768Button;
    private Button imageMax1024Button;
    private Button imageMaxOriginalButton;

    ImageSettingsSection(MainActivitySettingsHost host, ImageSettingsStore store,
            Supplier<ImageSettings> currentSettings, Consumer<ImageSettings> onSettingsSaved,
            Consumer<CharSequence> chatStatus) {
        this.host = host;
        this.store = store;
        this.currentSettings = currentSettings;
        this.onSettingsSaved = onSettingsSaved;
        this.chatStatus = chatStatus;
    }

    void mount(LinearLayout configuration, int ink, int inkMuted) {
        LinearLayout section = new LinearLayout(host.settingsActivity());
        section.setId(R.id.settings_images);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView heading = host.label(
                host.getString(R.string.settings_images_heading).toUpperCase(java.util.Locale.US), 11, inkMuted);
        heading.setTypeface(StillwaterUi.sansMedium(host.settingsActivity()));
        heading.setLetterSpacing(0.12f);
        heading.setPadding(0, host.dp(18), 0, host.dp(4));
        section.addView(heading);

        imagePrepareBox = new CheckBox(host.settingsActivity());
        imagePrepareBox.setId(R.id.settings_image_prepare);
        imagePrepareBox.setText(R.string.settings_image_prepare);
        StillwaterUi.styleCheck(imagePrepareBox);
        imagePrepareBox.setOnCheckedChangeListener((button, checked) -> {
            if (bindingImageSettings) return;
            ImageSettings current = currentSettings.get();
            persist(new ImageSettings(checked, current.maxSide, current.cropToSquare, readImageQualityField()), true);
        });
        section.addView(imagePrepareBox);
        section.addView(host.label(host.getString(R.string.settings_image_prepare_help), 12, inkMuted));

        TextView maxTitle = host.label(host.getString(R.string.settings_image_max_size), 15, ink);
        maxTitle.setTypeface(StillwaterUi.sansMedium(host.settingsActivity()));
        maxTitle.setPadding(0, host.dp(10), 0, 0);
        section.addView(maxTitle);
        section.addView(host.label(host.getString(R.string.settings_image_max_help), 12, inkMuted));

        imageMax384Button = imageMaxChoice(ImageSettings.MaxSide.SIDE_384, R.id.settings_image_max_384);
        imageMax512Button = imageMaxChoice(ImageSettings.MaxSide.SIDE_512, R.id.settings_image_max_512);
        imageMax768Button = imageMaxChoice(ImageSettings.MaxSide.SIDE_768, R.id.settings_image_max_768);
        LinearLayout maxRow1 = StillwaterReadable.addFlexibleButtonRow(section, host.settingsActivity(), 44,
                imageMax384Button, imageMax512Button, imageMax768Button);
        maxRow1.setId(R.id.settings_image_max_size);

        imageMax1024Button = imageMaxChoice(ImageSettings.MaxSide.SIDE_1024, R.id.settings_image_max_1024);
        imageMaxOriginalButton = imageMaxChoice(ImageSettings.MaxSide.ORIGINAL, R.id.settings_image_max_original);
        StillwaterReadable.addFlexibleButtonRow(section, host.settingsActivity(), 44,
                imageMax1024Button, imageMaxOriginalButton);

        imageCropBox = new CheckBox(host.settingsActivity());
        imageCropBox.setId(R.id.settings_image_crop);
        imageCropBox.setText(R.string.settings_image_crop);
        StillwaterUi.styleCheck(imageCropBox);
        imageCropBox.setOnCheckedChangeListener((button, checked) -> {
            if (bindingImageSettings) return;
            ImageSettings current = currentSettings.get();
            persist(new ImageSettings(current.enabled, current.maxSide, checked, readImageQualityField()), true);
        });
        LinearLayout.LayoutParams cropParams = new LinearLayout.LayoutParams(-1, -2);
        cropParams.topMargin = host.dp(6);
        section.addView(imageCropBox, cropParams);
        section.addView(host.label(host.getString(R.string.settings_image_crop_help), 12, inkMuted));

        imageQualityField = host.addSettingInput(section, host.getString(R.string.settings_image_quality),
                host.getString(R.string.settings_image_quality_help),
                currentSettings.get().jpegQuality, R.id.settings_image_quality, 5, 40, 95);
        imageQualityField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus || bindingImageSettings) return;
            try {
                int quality = Integer.parseInt(imageQualityField.getText().toString().trim());
                ImageSettings current = currentSettings.get();
                persist(new ImageSettings(current.enabled, current.maxSide, current.cropToSquare, quality), true);
            } catch (RuntimeException invalid) {
                syncFields();
                chatStatus.accept(invalid.getMessage());
            }
        });
        configuration.addView(section);
        syncFields();
    }

    /** Matches {@link MainActivity}'s bundled inference save (max side from stored model). */
    ImageSettings readForInlineInferenceSave() {
        ImageSettings current = currentSettings.get();
        return new ImageSettings(
                imagePrepareBox == null || imagePrepareBox.isChecked(),
                current.maxSide,
                imageCropBox != null && imageCropBox.isChecked(),
                readImageQualityField());
    }

    void syncFields() {
        if (imagePrepareBox == null) return;
        ImageSettings settings = currentSettings.get();
        bindingImageSettings = true;
        imagePrepareBox.setChecked(settings.enabled);
        if (imageCropBox != null) imageCropBox.setChecked(settings.cropToSquare);
        if (imageQualityField != null) imageQualityField.setText(Integer.toString(settings.jpegQuality));
        highlightImageMax(settings.maxSide);
        bindingImageSettings = false;
    }

    void persist(ImageSettings next, boolean announce) {
        if (store == null) return;
        try {
            ImageSettings validated = new ImageSettings(next.enabled, next.maxSide,
                    next.cropToSquare, next.jpegQuality);
            store.save(validated, () -> {
                if (host.isHostFinishing()) return;
                onSettingsSaved.accept(validated);
                syncFields();
                if (announce) chatStatus.accept(validated.summary());
            }, failure -> {
                if (host.isHostFinishing()) return;
                chatStatus.accept("Image settings were not saved: " + failure);
                syncFields();
            });
        } catch (IllegalArgumentException invalid) {
            chatStatus.accept(invalid.getMessage());
            syncFields();
        }
    }

    private Button imageMaxChoice(ImageSettings.MaxSide side, int id) {
        Button choice = host.button(side.label);
        choice.setId(id);
        StillwaterUi.outlined(choice);
        choice.setOnClickListener(v -> {
            if (bindingImageSettings) return;
            ImageSettings current = currentSettings.get();
            persist(new ImageSettings(current.enabled, side, current.cropToSquare, readImageQualityField()), true);
        });
        return choice;
    }

    private int readImageQualityField() {
        if (imageQualityField == null) return currentSettings.get().jpegQuality;
        try {
            return Integer.parseInt(imageQualityField.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            return currentSettings.get().jpegQuality;
        }
    }

    private void highlightImageMax(ImageSettings.MaxSide selected) {
        StillwaterUi.segmented(imageMax384Button, selected == ImageSettings.MaxSide.SIDE_384);
        StillwaterUi.segmented(imageMax512Button, selected == ImageSettings.MaxSide.SIDE_512);
        StillwaterUi.segmented(imageMax768Button, selected == ImageSettings.MaxSide.SIDE_768);
        StillwaterUi.segmented(imageMax1024Button, selected == ImageSettings.MaxSide.SIDE_1024);
        StillwaterUi.segmented(imageMaxOriginalButton, selected == ImageSettings.MaxSide.ORIGINAL);
    }
}
