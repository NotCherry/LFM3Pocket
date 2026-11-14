# ARM64 Omni physical evidence — RMX2170

## Device
- realme RMX2170, arm64-v8a, Android 12 / API 31
- MemTotal ~7.7 GiB
- Transport: Wi-Fi ADB 192.168.0.117

## Packages
- com.notcherry.lfm3pocket (phone APK from CI 43531276514)
- com.notcherry.lfm3pocket.devicecheck + .test (deviceCheck APKs, install -g)

## #84 Flashlight
- PASS — FlashlightCameraManagerTest with CAMERA granted; issue closed

## Text Omni probe (RD-02 / F01 text pathway)
- PASS — RealOmniProbeTest caseId=text, realOmni=true
- Wall time ~34.6s; validationMs=16103; loadMs=2320; firstTokenMs=5112; decodeMs=14514
- Output (Polish): Lokalne przetwarzanie to proces obróbki danych lub materiałów, który odbywa się w bezpośrednim otoczeniu użytkownika, co zwiększa efektywność i skróca czas obróbki.
- Weights on device: copies of host `LFM3-Omni-2B-Q4_0.gguf` and `mmproj-LFM3-Omni-2B-Q8_0.gguf` renamed to the probe's `Q4_K_M` filenames (quant names in JSON are labels only)
- Artifacts: probe-text.json, probe-text-stream.txt

## Image Omni probe
- NOT_RUN / TIMEOUT — instrumentation hung >20m in uninterruptible sleep before writing probe-image.json; force-stopped. Retest needed on USB or with keep-screen-on / smaller projector path.

## Audio / V02 / V03 Polish ASR
- NOT_RUN — no audio fixture pushed in this session

