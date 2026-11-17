# Text readability — font scale / density / locale

Owner-reported bug on Hanami v0.3.1: Settings buttons **Open model from storage** / **Open projector from storage** clipped their second line under a fixed `dp(48)` height in a two-column row.

## Evidence

| File | Notes |
| --- | --- |
| `settings-before-owner-phone-v0.3.1.jpg` | Owner phone Settings screenshot (clipped open-from-storage row). |
| `settings-after-font-1.0-en.png` | After fix at system `font_scale=1.0` (emulator). Buttons stack on narrow width; full labels visible. |
| `settings-after-font-2.0-en.png` | After fix at system `font_scale=2.0`. Wrapped “Open … from storage” stays fully readable (`minHeight` + wrap). |

## Coverage

- Production View Settings (`MainActivity`): flexible `minHeight` + vertical stack when width &lt; 400dp or fontScale ≥ 1.3
- Chat media row, idle unload row, Save actions
- Compose Home CTA (`heightIn`), Model manager labels (wrap, no ellipsis)
- Notification / consent action strings localized EN + PL
- Automated: `StillwaterReadableTest` (JVM), `TextReadableAtScaleE2eTest`, `ComposeTextReadableTest`

Capture PR shots locally with:

```bash
adb shell am instrument -w -e captureShots 1 \
  -e class com.notcherry.lfm3pocket.TextReadableAtScaleE2eTest#captureSettingsShotsAtSystemFontScale \
  dev.notcherry.hanami.test/com.notcherry.lfm3pocket.UiE2eTestRunner
```
