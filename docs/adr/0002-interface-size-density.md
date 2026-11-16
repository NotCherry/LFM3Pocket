# ADR 0002 — App-wide interface size via densityDpi context wrap

## Status

Accepted (Hanami Settings → Appearance → Interface size)

## Context

Users want denser chat chrome (modern assistant density) and a control to enlarge or shrink the whole UI on top of the system font scale. The app mixes View-based Settings/chat chrome with Compose screens, so a Compose-only `Density` override would leave Views behind.

## Decision

Persist an `interface_scale` multiplier (0.80–1.30) in `RuntimePreferences`. Each Activity wraps its base context in `attachBaseContext` with `InterfaceSize.wrap`, which multiplies `Configuration.densityDpi` while leaving system `fontScale` intact. Both `sp` and `dp` therefore move together in Views and Compose.

Activities also remember the scale applied at attach and call `InterfaceSize.recreateIfChanged` from `onResume`, so a back-stack host (Home, History) refreshes after Settings writes a new scale and recreates itself.

Touch targets stay at system 48dp: Compose via `LocalMinimumInteractiveComponentSize`, Views via `InterfaceSize.minTouchPx` (system display metrics).

## Consequences

- **Every new Activity** must call `InterfaceSize.wrap` + `recreateIfChanged` (DESIGN.md §11).
- Changing scale recreates the Settings Activity; other live Activities recreate on next resume.
- Application / Service contexts are not wrapped; do not assume scaled density outside Activities.
