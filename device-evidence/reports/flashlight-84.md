#84 Physical flashlight — PASS

- Device: realme RMX2170 (arm64-v8a), Android 12 / API 31
- Package: com.notcherry.lfm3pocket.devicecheck (install -g granted CAMERA)
- Test: FlashlightCameraManagerTest.cameraManagerPermissionAndCapabilityProduceStructuredResult
- Result: OK (1 test), elapsed ~0.115s with CAMERA granted=true
- Path: production AndroidFlashlightCamera / CameraManager setTorchMode on then off
- Timestamp: 2026-11-14T09:40:01.5112001+01:00
- APK: app-deviceCheck-debug.apk from CI run 43531276514
