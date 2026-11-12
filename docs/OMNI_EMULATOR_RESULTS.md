# Real Omni runs on the local Android emulator

These are real production-JNI runs with the published LFM3-Omni model/projector on an API 30 x86_64 emulator. They establish that this emulator build can validate, load and invoke the real text, image and audio paths. They do **not** qualify ARM64 devices, production phone performance, the Android picker/recorder UI, airplane-mode operation, speech generation, or combined audio+image input. Issue #13 stays open and AT-04 is PARTIAL.

## Reproducibility

- Emulator: `sdk_gphone_x86_64`, Android API 30, x86_64, 4 GiB configured RAM.
- Probe: `RealOmniProbeTest`, isolated `com.notcherry.lfm3pocket.devicecheck` app with the production `Lfm3PocketApplication` and native JNI. Emulator use requires explicit `-e allowEmulator true`.
- Model source: [LiquidAI/LFM3-Omni-2B-GGUF](https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/tree/d508b88f08a5b4286f2d2805a6a8d69a0ff44c72), revision `d508b88f08a5b4286f2d2805a6a8d69a0ff44c72`.
- Model `LFM3-Omni-2B-Q4_K_M.gguf`: 1,099,630,080 bytes; SHA-256 `9f8d30e9d4fd7a9349d586e077988229b3ca0a0e4ad730897563d1eccc9893e2`.
- Projector `mmproj-LFM3-Omni-2B-Q4_K_M.gguf`: 291,586,720 bytes; SHA-256 `0a746c0794782a16d4456b230c2a0b5888a2d94a6677211edbed7d7a4177a39a`.
- Image fixture: generated red square on the left and blue circle on the right; SHA-256 `796bc9c7f2335eafa1adcf90838d8686ec33bf9874f19d87cf7bf960592427f8`.
- Audio fixture: generated English speech, “The secret animal is a blue cat. The number is seven.”; SHA-256 `6fb91169177f97113009f31a8c664ccb3342c8eca53b61943b5fba8426c708d3`.
- Polish ASR probe fixture: eSpeak NG 1.52.0 local synthetic voice, “Moje hasło testowe to niebieski kot. Jaki kolor i jakie zwierzę wymieniłem?”; converted to 16 kHz mono PCM WAV; SHA-256 `f03cd65fa9e3fe54c760fb9778210bd6a362410915b3672cd979b40797a9d954`.
- Full device reports: [text](evidence/omni-emulator/text.json), [image](evidence/omni-emulator/image.json), [audio](evidence/omni-emulator/audio.json). Reports record the exact prompt, native output and phase timings. `quality` remains `REQUIRES_REVIEW`; the probe does not award quality scores.
- To repeat an ASR-only probe with the fixture installed as `files/models/speech.wav`, pass the pinned LFM3 ASR template as the user-turn prompt:

  ```bash
  prompt=$'<|startoftext|><|im_start|>system\nPerform ASR.<|im_end|>\n<|im_start|>user\n<__media__><|im_end|>\n<|im_start|>assistant\n'
  prompt64=$(printf '%s' "$prompt" | base64 -w0)
  adb -s emulator-5554 shell am instrument -w -r \
    -e class com.notcherry.lfm3pocket.RealOmniProbeTest \
    -e realOmni true -e allowEmulator true \
    -e media /data/user/0/com.notcherry.lfm3pocket.devicecheck/files/models/speech.wav \
    -e promptBase64 "$prompt64" -e caseId emulator-v03-asr-en -e maxTokens 96 \
    com.notcherry.lfm3pocket.devicecheck.test/androidx.test.runner.AndroidJUnitRunner
  ```

## Observed results

| Path | Result | Validation | Load | First token | Decode |
| --- | --- | ---: | ---: | ---: | ---: |
| Text, Polish prompt | PASS: non-empty Polish answer about local processing | 4,997 ms | 674 ms | 2,143 ms | 4,846 ms |
| Image, synthetic two-shape fixture | PASS on this fixture: identified red square left and blue circle right | 5,043 ms | 611 ms | 13,663 ms | 21,294 ms |
| Audio, synthetic English WAV, conversation prompt | PASS on this fixture: “The secret animal is a blue cat, the number is seven.” | 5,109 ms | 663 ms | 3,930 ms | 4,996 ms |
| Audio, same English WAV, ASR-only prompt | PASS on this fixture: “The secret animal is a blue cat the number is seven.” (words match; punctuation normalized) | 5,119 ms | 1,331 ms | 7,224 ms | 9,951 ms |
| Polish eSpeak NG WAV, ASR-only prompt | FAIL: generic prompt output `Ja kolo i akias nieje vimje im.`; explicit `Perform ASR in Polish.` output `Moja kasła testometonienieski to jakikolwiek jakieś niechębnie iem.`; neither matches ground truth | 5,105 ms / 5,242 ms | 1,041 ms / 905 ms | 7,006 ms / 7,947 ms | 10,847 ms / 13,895 ms |

The ASR-only runs used the pinned llama.cpp LFM3 prompts `Perform ASR.` and `Perform ASR in Polish.` with audio attached to the user turn. The English run matched its fixture; both Polish outputs were content failures despite `error: null`. Source commit `5ebf0cbb25c01013143bc816b8dc5e4f886d179e`, llama.cpp commit `8642541b255bfb5dc39cd351a178be09013a55d6`, deviceCheck APK SHA-256 `a26f8b7c308dfeac6b100fc8882c51fa0611f776f235ae7be8a564998aad86a4`, and the fixture hashes above identify these runs. They were executed on the local API 30 x86_64 emulator on 2026-11-12, not on ARM64 hardware. Recreate the Polish synthetic input with `espeak-ng -v pl --stdout 'Moje hasło testowe to niebieski kot. Jaki kolor i jakie zwierzę wymieniłem?' | ffmpeg -i pipe:0 -ar 16000 -ac 1 -c:a pcm_s16le polish-asr.wav`.

All instrumentation runs completed `OK (1 test)` with `error: null` and non-empty streamed output. These timings are from a virtual x86_64 CPU and must not be compared to a physical phone as release performance.

## Remaining acceptance work

- Repeat the probes on physical ARM64 hardware with the exact model/projector pair, or retain #13 as blocked on that hardware requirement.
- Exercise gallery import, camera capture, microphone recording, cancellation, offline use and sustained-load behavior through the app UI on supported phones.
- Verify Polish speech on representative human recordings, spoken model output, combined image+audio, and quality over broader fixtures. The current Polish synthetic-speech probe fails with both automatic and explicit-language ASR prompts; one eSpeak NG sample is not sufficient to conclude the production language path, but it is not acceptable transcript evidence. The ASR-only English probe covers only the English fixture.
