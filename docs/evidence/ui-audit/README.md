# UI audit emulator evidence

Phone-width frames (1080×2400) for [`docs/ui-audit.md`](../../ui-audit.md).

**APK:** local `phoneRelease` `0.3.1` / versionCode `3001` (2026-11-16 build) — lags `main` @ `2d81a3b`. Home on that APK lacked New chat; `main` always shows it (`home_new_chat`).

| File | What |
| --- | --- |
| `01-home.png` | Home hero; no-model footer; APK lag on New chat |
| `02-chat-empty.png` | Empty chat; duplicated mode; Clear; quiet model warning |
| `03-chat-overflow.png` | Overflow: Settings / Web search |
| `04-settings-top.png` | Model import + Load/Unload/Settings strip |
| `05-settings-mid.png` | SEARXNG / Background / Tools mid-scroll |
| `10-history-empty.png` | History empty EN chrome |
| `11-about.png` | About EN-only |
