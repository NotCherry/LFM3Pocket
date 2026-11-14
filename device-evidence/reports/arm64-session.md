# ARM64 Omni physical evidence — RMX2170

## Device
- realme RMX2170, arm64-v8a, Android 12 / API 31
- MemTotal ~7.7 GiB
- Transport: Wi-Fi ADB (mDNS `adb-e6ddd69-zGkO82._adb-tls-connect._tcp`)

## Packages
- com.notcherry.lfm3pocket (phone APK from CI 43531276514)
- com.notcherry.lfm3pocket.devicecheck + .test (deviceCheck APKs, `adb install -r -g`)

## #84 Flashlight
- PASS — FlashlightCameraManagerTest with CAMERA granted; issue closed

## Text Omni probe (RD-02 / F01–I03 pathway)
- PASS — RealOmniProbeTest caseId=text, realOmni=true
- Wall time ~34.6s; validationMs=16103; loadMs=2320; firstTokenMs=5112; decodeMs=14514
- Output (Polish): Lokalne przetwarzanie to proces obróbki danych lub materiałów, który odbywa się w bezpośrednim otoczeniu użytkownika, co zwiększa efektywność i skróca czas obróbki.
- Weights on device: copies of host `LFM3-Omni-2B-Q4_0.gguf` and `mmproj-LFM3-Omni-2B-Q8_0.gguf` renamed to the probe's `Q4_K_M` filenames (quant names in JSON are labels only)
- Artifacts: `probe-text.json`, `probe-text-rmx2170.json`, `probe-text-stream.txt`

## Image Omni probe
- PASS — RealOmniProbeTest `caseId=image`, realOmni=true (2026-11-14T13:17:23Z)
- Fixture: synthetic red background, blue square left, yellow circle right (`probe-objects.jpg`, SHA-256 `f0ffe0fe2483e89162a8b53e96c8dd9be79f26de4b8bc9a018b0befaa4d44ab2`)
- validationMs=15810; loadMs=4063; firstTokenMs=17737; decodeMs=22096; wall ~44s
- Output: `The image contains two shapes on a red background: a blue square on the left and a yellow circle on the right.`
- Artifacts: `probe-image.json`, `probe-image-rmx2170.json`, `probe-image-stream-rmx2170.txt`
- Earlier session TIMEOUT before `probe-image.json` is superseded by this PASS

## Audio / V02 / V03
### English ASR (`caseId=audio`)
- Native path PASS (`error: null`); content PARTIAL vs ground truth
- Fixture: Windows SAPI Zira → 16 kHz mono PCM (`speech.wav`, SHA-256 `9ebf4599bf5c833863a50ab4a60fd0ecce25d4cf7d5ef37ff3417a5d4c0422a9`); ground truth “The secret animal is a blue cat. The number is seven.”
- Prompt: pinned `Perform ASR.`
- Output: `The secret animal is a blanket. The number is seven.` (number OK; animal wrong)
- validationMs=16368; loadMs=2822; firstTokenMs=10767; decodeMs=13148
- Artifact: `probe-audio-en-rmx2170.json`

### Polish ASR (`caseId=audio-pl-asr`) — V03 accuracy
- PASS vs ground truth (exact transcript match)
- Fixture: Microsoft Paulina Desktop (pl-PL) → 16 kHz mono PCM (`polish-asr.wav`, SHA-256 `b646c9f96446fef1059e74bc0f56d78005161250502f8bf5a7bdc7b691c0d344`)
- Ground truth: `Moje hasło testowe to niebieski kot. Jaki kolor i jakie zwierzę wymieniłem?`
- Prompt: pinned `Perform ASR.`
- Output (exact): `Moje hasło testowe to niebieski kot. Jaki kolor i jakie zwierzę wymieniłem?`
- validationMs=16124; loadMs=3005; firstTokenMs=12230; decodeMs=16665
- Artifact: `probe-audio-pl-asr-rmx2170.json`
- Note: prior eSpeak NG synthetic Polish failed on emulator; SAPI pl-PL voice succeeds on this ARM64 device. Still not a human-mic recording.

### Polish audio interpretation (V02 native media)
- Native audio+answer prompts complete with `error: null` but tend to echo/translate rather than answer
- Best comprehension sample (`caseId=audio-pl-v02d`, production ChatSession system + “Listen to this recording and answer.”): `The test password is a blue cat. What color and what animal did I mention?` — understands content; does not answer
- Other variants (`audio-pl-v02`, `v02b`, `v02c`, `v02e`) echo Polish or emit `<|mixed_start|>…`
- Post-ASR text-only answer using the exact transcript (`caseId=audio-pl-answer-text`): PASS — `Twoje hasło testowe to niebieski kot. Wymieniłeś kolor niebieski i zwierzę kota.`
- Conclusion: ARM64 audio pathway + Polish ASR work; end-to-end “answer the spoken question” while media remains attached is still weak; answering after a successful ASR transcript works

## Mismatched projector (I03)
- PASS (safe rejection) — replaced `mmproj-LFM3-Omni-2B-Q4_K_M.gguf` with a language-model GGUF copy
- `GgufSelection.validate` error: `Select a multimodal projector, not a language model`
- Artifact: `probe-mismatch-rmx2170.json`; projector restored afterward

## ChatSession V02 wiring
- PR #113: successful ASR drops WAV from the assistant turn and answers from the transcript
- Phone APK from CI run 43601898557 installed on RMX2170

## Omni speech generation (AT-06)
- PASS — after PR #116 (`SpeechPrompt.tts` ends with `<think></think><|audio_start|>`)
- Input: speechText=`Hello`, en/female, maxFrames=256
- `sampleRateHz=24000`, mono, 16-bit; `pcmBytes=38400`; `firstAudioMs=4112`; `speechMs=6832`; `error=null`
- Artifacts: `probe-speech-rmx2170.json`, `probe-speech-rmx2170.wav`

## Diagnostics
- deviceCheck `diagnostics.ndjson` pulled: `diagnostics-devicecheck-rmx2170.ndjson` (MEMORY trim events)
- True airplane-mode TX still blocked on Wi-Fi ADB per protocol

## Limitations
- Host weights are Q4_0 + Q8_0 mmproj renamed to probe Q4_K_M filenames (same as text session)
- Audio fixtures are OS TTS, not human microphone captures
- `quality` field remains `REQUIRES_REVIEW` (probe never auto-grades)
- Gallery/camera/mic UI routes and airplane-mode traffic not exercised in these probes
- Compatibility summary: `docs/ARM64_COMPATIBILITY_REPORT.md`
