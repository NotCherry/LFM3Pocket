#!/usr/bin/env python3
"""Fail CI if connected Android tests failed, were skipped, or never executed.

Modes define explicit, tier-specific gate contracts:
- smoke: fast PR-required subset with real JNI emulator proof in connected run.
- deep: broad/nightly required suite (historical full gate).
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys
import tempfile
import xml.etree.ElementTree as ET

DEEP_REQUIRED_CLASSES = (
    "GenerationSmokeTest", "ChatUiE2eTest", "ComposeHostE2eTest",
    "ModelImportE2eTest", "ImageAttachmentE2eTest", "NativeLifetimeTest",
    "ModelManagerE2eTest", "PromptEncodingNativeTest", "InferenceSettingsE2eTest",
    "NativeSettingsTest", "ToolConsentE2eTest", "ToolChatE2eTest",
    "AdversarialAndroidActionE2eTest", "ZCameraCaptureE2eTest", "RecordingE2eTest",
    "HistorySchemaTest", "HistoryRepositoryTest", "HistoryPersistenceE2eTest",
    "HistoryNavigationE2eTest", "HistoryDeletionE2eTest", "HistoryJourneyE2eTest",
    "FlashlightCameraManagerTest", "AudioManagerVolumeTest", "AndroidTimerIntentTest",
    "AndroidAlarmIntentTest", "AndroidInstalledAppLauncherTest",
    "AndroidSettingsPageTest", "AndroidTextActionsTest", "VoiceOutputE2eTest",
    "MultimodalConversationE2eTest", "OfflinePrivacyE2eTest",
    "DiagnosticsStressE2eTest", "TextReadableAtScaleE2eTest",
    "ComposeTextReadableTest",
)
DEEP_MIN_TESTS = 87

# PR smoke gate: minimal connected proof set. GenerationSmokeTest provides a real
# JNI load/generate/stop/retry emulator proof; ComposeHostE2eTest covers app
# boot/Compose host. A cross-process persistence proof runs as a separate scripted
# instrumentation phase and is asserted by its own result gate.
SMOKE_REQUIRED_CLASSES = (
    "GenerationSmokeTest",
    "ComposeHostE2eTest",
)
SMOKE_MIN_TESTS = 2

MODES = {
    "smoke": (SMOKE_REQUIRED_CLASSES, SMOKE_MIN_TESTS),
    "deep": (DEEP_REQUIRED_CLASSES, DEEP_MIN_TESTS),
}
REQUIRED_CLASSES = DEEP_REQUIRED_CLASSES
MIN_TESTS = DEEP_MIN_TESTS


def verify(directory: Path, mode: str = "deep") -> tuple[int, int, int]:
    if mode not in MODES:
        raise ValueError(f"Unknown Android E2E gate mode: {mode}")
    required_classes, min_tests = MODES[mode]
    if not directory.is_dir():
        raise ValueError(f"Missing connected test result directory: {directory}")
    cases = []
    for path in directory.rglob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            raise ValueError(f"Unreadable Android JUnit XML {path}: {exc}") from exc
        for case in root.iter("testcase"):
            cases.append((path, case))
    if not cases:
        raise ValueError(
            f"No executed Android JUnit testcases found in {mode} tier "
            "(instrumentation may have been skipped)"
        )

    failed = sum(
        1 for _, case in cases
        if case.find("failure") is not None or case.find("error") is not None
    )
    skipped = sum(1 for _, case in cases if case.find("skipped") is not None)
    classes = {
        (case.attrib.get("classname", "") + " " + path.stem)
        for path, case in cases
    }
    missing = [
        name for name in required_classes
        if not any(name in class_name for class_name in classes)
    ]
    if len(cases) < min_tests or failed or skipped or missing:
        raise ValueError(
            f"Android E2E {mode} gate failed: tests={len(cases)}, failed={failed}, "
            f"skipped={skipped}, min_tests={min_tests}, missing_required={missing}"
        )
    return len(cases), failed, skipped


def self_test() -> None:
    """Prove this gate rejects failing XML and the silently-empty test case per tier."""
    with tempfile.TemporaryDirectory() as temp:
        directory = Path(temp)
        # Deep tier: historical full contract.
        root = ET.Element("testsuite")
        for i in range(DEEP_MIN_TESTS):
            name = DEEP_REQUIRED_CLASSES[i % len(DEEP_REQUIRED_CLASSES)]
            ET.SubElement(root, "testcase", classname=f"com.notcherry.{name}", name=f"test{i}")
        path = directory / "TEST-deep.xml"
        ET.ElementTree(root).write(path, encoding="utf-8")
        assert verify(directory, mode="deep") == (DEEP_MIN_TESTS, 0, 0)

        failing = ET.Element("testsuite")
        for i, name in enumerate(SMOKE_REQUIRED_CLASSES):
            ET.SubElement(failing, "testcase", classname=f"com.notcherry.{name}", name=f"smoke{i}")
        failing.find("testcase").append(ET.Element("failure", message="intentional regression"))
        smoke_path = directory / "TEST-smoke-fail.xml"
        ET.ElementTree(failing).write(smoke_path, encoding="utf-8")
        try:
            verify(directory, mode="smoke")
        except ValueError:
            pass
        else:
            raise AssertionError("Smoke gate accepted a failing instrumentation test")

        # Smoke tier: explicit minimal contract.
        smoke_ok = Path(temp) / "smoke-ok"
        smoke_ok.mkdir()
        ok_root = ET.Element("testsuite")
        for i, name in enumerate(SMOKE_REQUIRED_CLASSES):
            ET.SubElement(ok_root, "testcase", classname=f"com.notcherry.{name}", name=f"smoke{i}")
        ET.ElementTree(ok_root).write(smoke_ok / "TEST-smoke.xml", encoding="utf-8")
        assert verify(smoke_ok, mode="smoke") == (len(SMOKE_REQUIRED_CLASSES), 0, 0)

        # Smoke must reject missing required JNI proof.
        smoke_missing = Path(temp) / "smoke-missing"
        smoke_missing.mkdir()
        miss_root = ET.Element("testsuite")
        ET.SubElement(miss_root, "testcase", classname="com.notcherry.ComposeHostE2eTest", name="boot")
        ET.ElementTree(miss_root).write(smoke_missing / "TEST-smoke.xml", encoding="utf-8")
        try:
            verify(smoke_missing, mode="smoke")
        except ValueError:
            pass
        else:
            raise AssertionError("Smoke gate accepted run missing required JNI proof")

        # Empty run rejected.
        empty = Path(temp) / "empty"
        empty.mkdir()
        try:
            verify(empty, mode="deep")
        except ValueError:
            pass
        else:
            raise AssertionError("Gate accepted an empty instrumentation run")
    print("Negative controls passed: failing, missing-required, and zero-test XML were rejected for tiers.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?", default="app/build/outputs/androidTest-results/connected",
                        help="Connected JUnit XML directory")
    parser.add_argument("--mode", choices=sorted(MODES), default="deep",
                        help="E2E gate tier contract to enforce")
    parser.add_argument("--self-test", action="store_true", help="Run negative controls")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    directory = Path(args.directory)
    try:
        tests, failed, skipped = verify(directory, mode=args.mode)
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as output:
                output.write(f"### Android emulator tests ({args.mode}): FAILED\n\n{exc}\n")
        sys.exit(1)

    line = (
        f"Android emulator connected tests ({args.mode}): {tests} executed, "
        f"{failed} failed, {skipped} skipped"
    )
    print(line)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write(f"### Android emulator tests ({args.mode}): PASSED\n\n{line}\n")


if __name__ == "__main__":
    main()
