# Voice V1 loop — arm64 device checklist

See [voice-actions plan](plans/voice-actions.md) and [issue index/audit](plans/voice-actions-issues.md) for full context. Record PASS / FAIL / NOT_RUN with device identity and APK SHA.

**Audit note (2026-11-18):** V1 implementation shipped in [#180](https://github.com/NotCherry/LFM3Pocket/pull/180) and [#158](https://github.com/NotCherry/LFM3Pocket/issues/158) is closed, but no physical arm64 run has been recorded in this repository yet. Each case below remains **NOT_RUN / unrecorded** until evidence is added.

| ID | Action | Expect | Status (2026-11-18) |
| --- | --- | --- | --- |
| VL-01 | Omni loaded; Polish PTT command | Grounded local reply; no network STT | NOT_RUN / unrecorded |
| VL-02 | Shade Talk, screen off | Listen → reply while FGS up | NOT_RUN / unrecorded |
| VL-03 | Propose alarm; say **tak** | Exact read-back; approve once | NOT_RUN / unrecorded |
| VL-04 | Propose; say **nie** / garbage | Reject; no side effect | NOT_RUN / unrecorded |
| VL-05 | Text-only GGUF | Talk disabled; clear pl+en message | NOT_RUN / unrecorded |
| VL-06 | Stop during listen / speech | Clean idle; no stuck FGS mic | NOT_RUN / unrecorded |

## Evidence template

| Field | Value |
| --- | --- |
| Date | |
| Device / SoC | |
| Android version | |
| APK build / commit SHA | |
| Omni GGUF SHA | |
| mmproj SHA | |
| Notes / logs (redacted) | |

Evidence rules: follow [ARM64_OMNI_TEST_PROTOCOL.md](ARM64_OMNI_TEST_PROTOCOL.md); do not fabricate PASS.
