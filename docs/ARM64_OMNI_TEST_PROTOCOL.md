# Real-device LFM3-Omni verification protocol (T08 / #12)

**This protocol has not yet been executed on a physical phone.** It defines repeatable, human-observed evidence for the Stage 1 release gates. GitHub Actions x86_64 emulator tests use a scripted fake or compact Stories15M JNI fixture; they **do not** prove real Omni audio, image, speech generation, tool calling or phone performance.

## Prerequisites and provenance

Use a **physical** Android device reporting an `arm64-v8a` ABI. Install the current ARM64 debug APK from a passing GitHub Actions build. Import an official, license-compatible, locally downloaded **LFM3-Omni** GGUF and its **matching mmproj projector** through the app's Android document picker, or mark an unsupported pathway explicitly. Verify model provenance against the publisher's official release page; **file names alone do not establish matching weights**. The APK and models must be imported while online, then the test can disable connectivity.

Capture the exact device manufacturer/model, Android API version, build fingerprint, RAM, APK version, APK SHA256, GGUF file names/sizes/SHA256, matching publisher/revision/license, llama.cpp revision, test timestamp and whether the device is offline. Do not publish model files, personal images, voice recordings or raw unreviewed logcat.

The companion host-side script collects **actual** device properties and hashes, never fabricates a passing test or metrics:

```bash
bash .github/scripts/collect-arm64-omni-evidence.sh --self-test
bash .github/scripts/collect-arm64-omni-evidence.sh \
  --model /path/to/LFM3-Omni-2B-Q4_K_M.gguf \
  --projector /path/to/mmproj-LFM3-Omni-2B-Q4_K_M.gguf \
  --apk /path/to/app-phone-debug.apk \
  --out omni-real-device-evidence
```

Add `--serial DEVICE_SERIAL` if multiple adb devices are attached. For a second resource snapshot, use `--phase post` with the same output directory. The script rejects an emulator and non-ARM64 devices, missing GGUF files, invalid GGUF magic, or an uninstalled app. It emits a metadata manifest, a checklist with **NOT_RUN** outcomes, package/CPU/memory/thermal/battery reports, and optionally `--logcat` if explicitly requested. Do not treat collecting evidence as test execution.

## Procedure: record each result as PASS, FAIL, UNSUPPORTED, or NOT_RUN

Before testing, record device identity, model checksum, projector checksum and app build in `manual-results.md`. Run the evidence script once at baseline. Ensure all incoming calls/notifications are disabled and the phone is unplugged if measuring battery, unless the run is specifically measuring charging behavior.

| Case | Reproducible action | Observable acceptance | Evidence and caveat |
| --- | --- | --- | --- |
| RD-01 | Import official text/Omni GGUF and its matching projector using SAF; restart Activity | Valid import/load, no crash, same model restored, no silently discarded projector | Screenshot **only with user consent**, model SHA, log if failure |
| RD-02 | Disable Wi-Fi and cellular/enable airplane mode; type `Napisz po polsku jedno zdanie o lokalnym przetwarzaniu.` and Send | Meaningful streamed Polish reply produced locally; record first-token latency and total completion | Actual text output (redact secrets), stopwatch ms; not a fake scripted reply |
| RD-03 | Ask for a long response, press Stop after visible text, then send another question | Stop becomes effective and retry responds without app freeze or bad model state | Device logs and timing; record Stop latency |
| RD-04 | Attach a still image from gallery showing two distinguishable, non-private objects; ask `Co jest na zdjęciu?` | Answer demonstrably references actual photographed content, not generic template | Record prompt, ground-truth object descriptions, exact output |
| RD-05 | Capture an original non-sensitive camera still and ask an object-specific question | Correct attachment and grounded visual response; record CameraX/camera support limitations | Record capture permission/intent path and answer |
| RD-06 | Record short Polish speech with a distinct fact (e.g. `Moje hasło testowe to niebieski kot.`) | Audio interpreted locally and answer refers to spoken fact without manually typing it | Record spoken words and response; no personal audio upload |
| RD-07 | Request a spoken answer and stop playback | Local model-generated speech is audible and interruptible | Record output duration/latency; **UNSUPPORTED** if stage not implemented |
| RD-08 | Combine attached photo and spoken question in the same turn | Response grounds both audio and image, not text-only fallback | Record exact media contents and output; **UNSUPPORTED** until wired |
| RD-09 | Request a safe validated action and a sensitive action | Safe action only via typed allowlist; sensitive action requires explicit consent and denied action is inert | Capture approval + denial states; **UNSUPPORTED** until action layer implemented |
| RD-10 | Send several turns, force-stop and relaunch; delete conversation | Transcript persists correctly, deletion removes private history/media; no cross-session AI memory | Check storage via permitted app UI; **UNSUPPORTED** until Room persistence implemented |
| RD-11 | Deny microphone permission, repeat RD-02; leave search disabled | Text inference works; no network dependency or permission crash | Record connectivity, package UID counters if supported, permissions |
| RD-12 | Run 10-minute repeated inference; measure peak RSS, thermal, battery, app crash/ANR | No crash/ANR; document observed resource values and device throttling, without inventing thresholds | Baseline/post dumpsys, timestamps, workload details |

For each case include `case_id | status | observed | evidence file | reason/issue`. Use **FAIL** for a tested broken behavior, **UNSUPPORTED** for not implemented, and **NOT_RUN** until the procedure was attempted. Never silently call an unavailable capability PASS. See `docs/ACCEPTANCE_MATRIX.md` for AT-01..AT-09 acceptance linkage and ownership.

## Privacy and reproducibility boundaries

The host script uses `adb` against a device explicitly selected by its operator and only reads package/device diagnostics. It does **not** install an APK, write GGUF files to Android storage, click through confirmations, upload data, capture screenshots, or record audio. Raw logcat requires `--logcat` and may contain sensitive information; review/redact before sharing. Output stays on the host.

The script's `--self-test` creates temporary four-byte GGUF signature fixtures and validates rejection of bad input without requiring Android or network access. This proves input validation only, not Omni functionality.

Link a signed-off redacted device report and its model checksums to issue #13 (F01) before promoting any real-device release requirement from PARTIAL/MISSING to VERIFIED.

## Isolated installation when the phone already has another signing key

`deviceCheck` uses the same production sources/JNI with application ID `com.notcherry.lfm3pocket.devicecheck` and label **LFM3 Pocket Device Test**. It installs beside the regular app, preserving that app's selected models/data. Its instrumentation runner is the ordinary AndroidJUnitRunner, with production dependencies; the probe explicitly rejects the scripted test Application. CI builds both isolated APKs in the ARM64 job. Record the actual APK hash, source commit and application ID; do not describe a local build as a downloaded CI artifact.

Build/install only onto the explicitly selected physical phone:

```bash
gradle :app:assembleDeviceCheckDebug :app:assembleDeviceCheckDebugAndroidTest
adb -s "$PHONE_SERIAL" install -r app/build/outputs/apk/deviceCheck/debug/app-deviceCheck-debug.apk
adb -s "$PHONE_SERIAL" install -r app/build/outputs/apk/androidTest/deviceCheck/debug/app-deviceCheck-debug-androidTest.apk
adb -s "$PHONE_SERIAL" shell run-as com.notcherry.lfm3pocket.devicecheck mkdir -p /data/user/0/com.notcherry.lfm3pocket.devicecheck/files/models
adb -s "$PHONE_SERIAL" shell -T run-as com.notcherry.lfm3pocket.devicecheck dd of=/data/user/0/com.notcherry.lfm3pocket.devicecheck/files/models/LFM3-Omni-2B-Q4_K_M.gguf < /path/to/LFM3-Omni-2B-Q4_K_M.gguf
adb -s "$PHONE_SERIAL" shell -T run-as com.notcherry.lfm3pocket.devicecheck dd of=/data/user/0/com.notcherry.lfm3pocket.devicecheck/files/models/mmproj-LFM3-Omni-2B-Q4_K_M.gguf < /path/to/mmproj-LFM3-Omni-2B-Q4_K_M.gguf
adb -s "$PHONE_SERIAL" shell am instrument -w -r \
  -e class com.notcherry.lfm3pocket.RealOmniProbeTest -e realOmni true \
  com.notcherry.lfm3pocket.devicecheck.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$PHONE_SERIAL" shell run-as com.notcherry.lfm3pocket.devicecheck cat /data/user/0/com.notcherry.lfm3pocket.devicecheck/files/probe-text.json > probe-text.json
```

RealOmniProbeTest validates actual GGUF metadata, loads the real model/projector and records exact native text, errors and timings in app-private files named probe-<case>.json and probe-<case>-stream.txt. Use run-as cat to copy the report out of app storage. Optional `-e media PATH`, `-e prompt PROMPT`, `-e promptBase64 BASE64_UTF8_PROMPT`, and `-e caseId image` allow controlled non-private fixtures. Base64 is useful for prompts with shell-sensitive model tokens such as `<|im_start|>`. Host evidence collection supports `--package com.notcherry.lfm3pocket.devicecheck`. A successful native call does not grade language/vision/audio quality, prove the SAF/UI path or verify offline operation. Review recorded output against fixture ground truth and report each limitation separately. Never run the unfiltered fake UI suite with the production-dependency device runner.

Wi-Fi ADB requires Wi-Fi connectivity; do not disconnect it during an unattended run or call such a run an airplane-mode test. Offline acceptance requires an on-phone/manual handoff or a working USB link.

## Local x86_64 emulator run

When a physical ARM64 phone is unavailable, the same isolated probe can load publisher-provided model weights in the local x86_64 emulator. This verifies the GGUF preflight and the x86_64 JNI/model/projector path and records real generated output. It does **not** satisfy ARM64 compatibility, real-phone performance, radio-off behavior, microphone/camera hardware behavior, or the physical-device release gate. Keep issue #13 PARTIAL until its ARM64 evidence is available.

The deviceCheck flavor packages both arm64-v8a and x86_64 libraries. Use an emulator with at least 4 GB RAM and enough free data storage for both model files:

```bash
gradle :app:assembleDeviceCheckDebug :app:assembleDeviceCheckDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/deviceCheck/debug/app-deviceCheck-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/deviceCheck/debug/app-deviceCheck-debug-androidTest.apk
adb -s emulator-5554 shell run-as com.notcherry.lfm3pocket.devicecheck mkdir -p /data/user/0/com.notcherry.lfm3pocket.devicecheck/files/models
adb -s emulator-5554 shell -T run-as com.notcherry.lfm3pocket.devicecheck dd of=/data/user/0/com.notcherry.lfm3pocket.devicecheck/files/models/LFM3-Omni-2B-Q4_K_M.gguf < /path/to/LFM3-Omni-2B-Q4_K_M.gguf
adb -s emulator-5554 shell -T run-as com.notcherry.lfm3pocket.devicecheck dd of=/data/user/0/com.notcherry.lfm3pocket.devicecheck/files/models/mmproj-LFM3-Omni-2B-Q4_K_M.gguf < /path/to/mmproj-LFM3-Omni-2B-Q4_K_M.gguf
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.notcherry.lfm3pocket.RealOmniProbeTest -e realOmni true \
  -e allowEmulator true -e caseId emulator-text -e maxTokens 64 \
  com.notcherry.lfm3pocket.devicecheck.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell run-as com.notcherry.lfm3pocket.devicecheck cat /data/user/0/com.notcherry.lfm3pocket.devicecheck/files/probe-emulator-text.json > probe-emulator-text.json
```

The emulator opt-in is explicit. Reports label the environment as EMULATOR and record its ABI. Do not feed these results to the physical-device evidence collector or relabel them as ARM64 phone results.
