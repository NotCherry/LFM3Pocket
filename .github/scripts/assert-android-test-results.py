#!/usr/bin/env python3
"""Fail CI if connected Android tests failed, were skipped, or never executed.

Modes define explicit, tier-specific gate contracts:
- smoke: fast PR-required connected subset. Must include a real JNI emulator
  proof (GenerationSmokeTest) and core app/Compose+chat UI proof
  (ComposeHostE2eTest). A cross-process persistence proof runs as a separate
  scripted instrumentation phase and is asserted by its own result gate.
- deep: broad/nightly connected suite. Includes the full intended androidTest
  class set (moved heavy/matrix/stress/native-deep coverage) so nothing is
  silently executed or required outside this explicit contract.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

# PR smoke gate: minimal high-signal connected proof set.
# GenerationSmokeTest: real JNI load/generate/stream/stop/retry emulator proof.
# ComposeHostE2eTest: app boot + Compose host + core chat/UI flow on fake engine.
SMOKE_REQUIRED_CLASSES = (
    "GenerationSmokeTest",
    "ComposeHostE2eTest",
)
SMOKE_MIN_TESTS = len(SMOKE_REQUIRED_CLASSES)

# Deep/nightly gate: explicit broad connected suite contract. This is the full
# intended instrumented test-class set for the emulator E2E job (all androidTest
# classes except non-test harness/helpers), including moved heavy/deep coverage
# (GenerationDeepSmokeTest, TextReadableAtScaleE2eTest, DiagnosticsStressE2eTest,
# matrices and stress/contract tests). Keep in sync with run-jni-smoke.sh.
DEEP_REQUIRED_CLASSES = (
    "AdversarialAndroidActionE2eTest",
    "AndroidAlarmIntentTest",
    "AndroidInstalledAppLauncherTest",
    "AndroidSettingsPageTest",
    "AndroidTextActionsTest",
    "AndroidTimerIntentTest",
    "AssistantVoiceBootstrapE2eTest",
    "AudioManagerVolumeTest",
    "BackgroundNotificationE2eTest",
    "CactusWhistleSttSmokeTest",
    "ChatLiveStatusContractTest",
    "ChatNewChatE2eTest",
    "ChatUiE2eTest",
    "ComposeHostE2eTest",
    "ComposeTextReadableTest",
    "DependencyContractTest",
    "DiagnosticsStressE2eTest",
    "FlashlightCameraManagerTest",
    "GenerationDeepSmokeTest",
    "GenerationSmokeTest",
    "HistoryDeletionE2eTest",
    "HistoryJourneyE2eTest",
    "HistoryNavigationE2eTest",
    "HistoryPersistenceE2eTest",
    "HistoryRepositoryTest",
    "HistorySchemaTest",
    "ImageAttachmentE2eTest",
    "InferenceSettingsE2eTest",
    "LoopbackAgentApiInstrumentationTest",
    "ModelImportE2eTest",
    "ModelManagerE2eTest",
    "MultimodalConversationE2eTest",
    "NativeLifetimeTest",
    "NativeSettingsTest",
    "OfflinePrivacyE2eTest",
    "OfflineTrafficE2eTest",
    "PromptEncodingNativeTest",
    "RecordingE2eTest",
    "TextReadableAtScaleE2eTest",
    "ToolChatE2eTest",
    "ToolConsentE2eTest",
    "VoiceOutputE2eTest",
    "VoiceUiStateE2eTest",
    "ZCameraCaptureE2eTest",
)
# Harness/helpers under androidTest that are not instrumented JUnit classes (runners,
# fakes, fixtures). Every value must be a short justification for the exclusion.
EXCLUDED_CLASSES: dict[str, str] = {
    "ImportDocumentsProvider": "SAF DocumentsProvider fixture packaged in the test APK",
    "TestGgufFixtures": "GGUF byte fixtures for preflight tests; not a JUnit class",
    "TestLfm3PocketApplication": "Fake Application wired by UiE2eTestRunner for UI E2E",
    "UiE2eTestRunner": "AndroidJUnitRunner that installs TestLfm3PocketApplication",
}
# Historical floor plus headroom; actual executed tests are substantially higher.
DEEP_MIN_TESTS = 89

_HAS_TEST_ANNOTATION = re.compile(r"@Test\b")
_TEST_CLASS_SUFFIXES = ("Test", "E2eTest")

MODES = {
    "smoke": (SMOKE_REQUIRED_CLASSES, SMOKE_MIN_TESTS),
    "deep": (DEEP_REQUIRED_CLASSES, DEEP_MIN_TESTS),
}
REQUIRED_CLASSES = DEEP_REQUIRED_CLASSES
MIN_TESTS = DEEP_MIN_TESTS
PACKAGE = "com.notcherry.lfm3pocket"


def repo_android_test_root() -> Path:
    """Return app/src/androidTest relative to the repository root."""
    return Path(__file__).resolve().parents[2] / "app" / "src" / "androidTest"


def _class_source_paths(android_test_root: Path, simple_name: str) -> list[Path]:
    return list(android_test_root.rglob(f"{simple_name}.java")) + list(
        android_test_root.rglob(f"{simple_name}.kt")
    )


def is_instrumented_test_class(simple_name: str, source_text: str) -> bool:
    """True when a source file looks like an instrumented JUnit test class."""
    if any(simple_name.endswith(suffix) for suffix in _TEST_CLASS_SUFFIXES):
        return True
    return _HAS_TEST_ANNOTATION.search(source_text) is not None


def discover_android_test_classes(android_test_root: Path) -> set[str]:
    """Scan androidTest sources for classes that should appear in the tier inventory."""
    if not android_test_root.is_dir():
        raise ValueError(f"Missing androidTest source tree: {android_test_root}")
    discovered: set[str] = set()
    for path in sorted(android_test_root.rglob("*")):
        if path.suffix not in {".java", ".kt"}:
            continue
        simple_name = path.stem
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            raise ValueError(f"Unreadable androidTest source {path}: {exc}") from exc
        if is_instrumented_test_class(simple_name, text):
            discovered.add(simple_name)
    return discovered


def check_inventory(
    android_test_root: Path | None = None,
    *,
    deep_classes: tuple[str, ...] | None = None,
    excluded_classes: dict[str, str] | None = None,
    smoke_classes: tuple[str, ...] | None = None,
) -> None:
    """Fail when androidTest classes drift from DEEP_REQUIRED_CLASSES / EXCLUDED_CLASSES."""
    root = android_test_root or repo_android_test_root()
    discovered = discover_android_test_classes(root)
    deep = set(deep_classes if deep_classes is not None else DEEP_REQUIRED_CLASSES)
    excluded_map = excluded_classes if excluded_classes is not None else EXCLUDED_CLASSES
    excluded = set(excluded_map)
    smoke = set(smoke_classes if smoke_classes is not None else SMOKE_REQUIRED_CLASSES)

    missing_justification = [
        name for name, reason in excluded_map.items() if not str(reason).strip()
    ]
    if missing_justification:
        raise ValueError(
            "EXCLUDED_CLASSES missing justification: "
            + ", ".join(sorted(missing_justification))
        )

    if not smoke.issubset(deep):
        raise ValueError(
            "SMOKE_REQUIRED_CLASSES must be a subset of DEEP_REQUIRED_CLASSES: "
            + ", ".join(sorted(smoke - deep))
        )

    overlap = deep & excluded
    if overlap:
        raise ValueError(
            "Class cannot be both required and excluded: " + ", ".join(sorted(overlap))
        )

    stale: list[str] = []
    for name in sorted(deep | excluded | smoke):
        if not _class_source_paths(root, name):
            stale.append(name)
    if stale:
        raise ValueError(
            "Stale inventory entries (no androidTest source file): " + ", ".join(stale)
        )

    unlisted = sorted(discovered - deep - excluded)
    if unlisted:
        raise ValueError(
            "androidTest classes not in DEEP_REQUIRED_CLASSES or EXCLUDED_CLASSES: "
            + ", ".join(unlisted)
        )

    non_tests_in_deep = sorted(deep - discovered)
    if non_tests_in_deep:
        raise ValueError(
            "DEEP_REQUIRED_CLASSES entries are not instrumented test classes: "
            + ", ".join(non_tests_in_deep)
        )


def required_class_args(mode: str) -> str:
    """Return fully-qualified instrumentation class filter for mode."""
    if mode not in MODES:
        raise ValueError(f"Unknown Android E2E gate mode: {mode}")
    required_classes, _ = MODES[mode]
    return ",".join(f"{PACKAGE}.{name}" for name in required_classes)


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
    assert required_class_args("smoke").count(",") + 1 == len(SMOKE_REQUIRED_CLASSES)
    assert required_class_args("deep").count(",") + 1 == len(DEEP_REQUIRED_CLASSES)
    assert "GenerationSmokeTest" in SMOKE_REQUIRED_CLASSES
    assert "ComposeHostE2eTest" in SMOKE_REQUIRED_CLASSES
    assert set(SMOKE_REQUIRED_CLASSES).issubset(set(DEEP_REQUIRED_CLASSES))
    with tempfile.TemporaryDirectory() as temp:
        directory = Path(temp)
        # Deep tier: explicit broad contract with all required classes present.
        root = ET.Element("testsuite")
        for i in range(DEEP_MIN_TESTS):
            name = DEEP_REQUIRED_CLASSES[i % len(DEEP_REQUIRED_CLASSES)]
            ET.SubElement(root, "testcase", classname=f"{PACKAGE}.{name}", name=f"test{i}")
        path = directory / "TEST-deep.xml"
        ET.ElementTree(root).write(path, encoding="utf-8")
        assert verify(directory, mode="deep") == (DEEP_MIN_TESTS, 0, 0)

        failing = ET.Element("testsuite")
        for i, name in enumerate(SMOKE_REQUIRED_CLASSES):
            ET.SubElement(failing, "testcase", classname=f"{PACKAGE}.{name}", name=f"smoke{i}")
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
            ET.SubElement(ok_root, "testcase", classname=f"{PACKAGE}.{name}", name=f"smoke{i}")
        ET.ElementTree(ok_root).write(smoke_ok / "TEST-smoke.xml", encoding="utf-8")
        assert verify(smoke_ok, mode="smoke") == (len(SMOKE_REQUIRED_CLASSES), 0, 0)

        # Smoke must reject missing required JNI proof.
        smoke_missing = Path(temp) / "smoke-missing"
        smoke_missing.mkdir()
        miss_root = ET.Element("testsuite")
        ET.SubElement(miss_root, "testcase", classname=f"{PACKAGE}.ComposeHostE2eTest", name="boot")
        ET.ElementTree(miss_root).write(smoke_missing / "TEST-smoke.xml", encoding="utf-8")
        try:
            verify(smoke_missing, mode="smoke")
        except ValueError:
            pass
        else:
            raise AssertionError("Smoke gate accepted run missing required JNI proof")

        # Deep must reject missing required class from explicit broad contract.
        deep_missing = Path(temp) / "deep-missing"
        deep_missing.mkdir()
        deep_root = ET.Element("testsuite")
        missing_pool = DEEP_REQUIRED_CLASSES[1:]
        for i in range(DEEP_MIN_TESTS):
            name = missing_pool[i % len(missing_pool)]
            ET.SubElement(deep_root, "testcase", classname=f"{PACKAGE}.{name}", name=f"test{i}")
        ET.ElementTree(deep_root).write(deep_missing / "TEST-deep.xml", encoding="utf-8")
        try:
            verify(deep_missing, mode="deep")
        except ValueError:
            pass
        else:
            raise AssertionError("Deep gate accepted run missing a required class")

        # Empty run rejected.
        empty = Path(temp) / "empty"
        empty.mkdir()
        try:
            verify(empty, mode="deep")
        except ValueError:
            pass
        else:
            raise AssertionError("Gate accepted an empty instrumentation run")

        inventory_root = Path(temp) / "inventory"
        pkg = inventory_root / "java/com/notcherry/lfm3pocket"
        pkg.mkdir(parents=True)
        stale_contract = ("ListedButRemovedTest",)
        try:
            check_inventory(
                inventory_root,
                deep_classes=stale_contract,
                excluded_classes={},
                smoke_classes=stale_contract,
            )
        except ValueError as exc:
            if "Stale inventory" not in str(exc):
                raise AssertionError(f"Expected stale inventory error, got: {exc}") from exc
        else:
            raise AssertionError("Inventory check accepted a stale DEEP_REQUIRED_CLASSES entry")

        enrolled = ("EnrolledTest",)
        (pkg / "EnrolledTest.java").write_text(
            "public class EnrolledTest { @org.junit.Test public void t() {} }",
            encoding="utf-8",
        )
        (pkg / "OrphanNewTest.java").write_text(
            "public class OrphanNewTest { @org.junit.Test public void t() {} }",
            encoding="utf-8",
        )
        try:
            check_inventory(
                inventory_root,
                deep_classes=enrolled,
                excluded_classes={},
                smoke_classes=enrolled,
            )
        except ValueError as exc:
            if "not in DEEP_REQUIRED_CLASSES" not in str(exc):
                raise AssertionError(f"Expected unlisted class error, got: {exc}") from exc
        else:
            raise AssertionError("Inventory check accepted an unlisted androidTest class")

    if repo_android_test_root().is_dir():
        check_inventory()
    print(
        "Negative controls passed: failing, missing-required, and zero-test XML were rejected; "
        "inventory check rejects stale and unlisted classes."
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", nargs="?", default="app/build/outputs/androidTest-results/connected",
                        help="Connected JUnit XML directory")
    parser.add_argument("--mode", choices=sorted(MODES), default="deep",
                        help="E2E gate tier contract to enforce")
    parser.add_argument("--self-test", action="store_true", help="Run negative controls")
    parser.add_argument("--list-classes", action="store_true",
                        help="Print comma-separated fully-qualified test classes for --mode")
    parser.add_argument(
        "--check-inventory",
        action="store_true",
        help="Verify androidTest sources match DEEP_REQUIRED_CLASSES and EXCLUDED_CLASSES",
    )
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.check_inventory:
        try:
            check_inventory()
        except ValueError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            sys.exit(1)
        print(
            "Android instrumentation inventory OK: "
            f"{len(DEEP_REQUIRED_CLASSES)} deep classes, "
            f"{len(EXCLUDED_CLASSES)} excluded helpers"
        )
        return
    if args.list_classes:
        try:
            print(required_class_args(args.mode))
        except ValueError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            sys.exit(1)
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
