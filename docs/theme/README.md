# Theme tokens

Authoritative Stillwater / Atlas design tokens live in:

| Layer | Path |
| --- | --- |
| Spec | [`docs/DESIGN.md`](../DESIGN.md) |
| Compose | `app/src/main/java/com/notcherry/lfm3pocket/ui/theme/` (`AtlasColors`, `AtlasTypography`, `AtlasShapes`, `AtlasSpacing`, `PocketTheme`, `StillwaterControls`) |
| View | `StillwaterUi.java`, `InterfaceSize.java`, `StillwaterReadable.java` |
| XML | `app/src/main/res/values/colors.xml` (`still_*`) |

**Rules for new UI code**

1. Prefer `MaterialTheme.atlasColors` / `atlasTypography` / `atlasSpacing` / `MaterialTheme.shapes` in Compose.
2. Prefer `StillwaterUi.*` / `InterfaceSize.minTouchPx` in View code.
3. Do not introduce one-off `Color(0x…)` / `0xFF……` hex in UI packages — CI runs `.github/scripts/check-ui-tokens.py`.
4. Keep Interface size presets (#175 / ADR 0002) working; compact default density stays.
5. Production Settings is `MainActivity`’s View scroll, not `SettingsScreen.kt` (recipe/test host).

Usability backlog: `docs/ui-audit.md` (lands via Phase 1 audit PR).
