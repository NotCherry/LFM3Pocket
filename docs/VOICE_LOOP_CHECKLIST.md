# Voice V1 loop — arm64 device checklist

See [voice-actions plan](plans/voice-actions.md) (PR #149) for full context. Record PASS / FAIL / NOT_RUN with device identity and APK SHA.

| ID | Action | Expect |
| --- | --- | --- |
| VL-01 | Omni loaded; Polish PTT command | Grounded local reply; no network STT |
| VL-02 | Shade Talk, screen off | Listen → reply while FGS up |
| VL-03 | Propose alarm; say **tak** | Exact read-back; approve once |
| VL-04 | Propose; say **nie** / garbage | Reject; no side effect |
| VL-05 | Text-only GGUF | Talk disabled; clear pl+en message |
| VL-06 | Stop during listen / speech | Clean idle; no stuck FGS mic |

Evidence rules: follow [ARM64_OMNI_TEST_PROTOCOL.md](ARM64_OMNI_TEST_PROTOCOL.md); do not fabricate PASS.