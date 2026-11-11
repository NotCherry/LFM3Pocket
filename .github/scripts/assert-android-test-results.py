#!/usr/bin/env python3
"""Fail CI if connected Android tests failed, were skipped, or never executed."""
from __future__ import annotations

import os
from pathlib import Path
import sys
import tempfile
import xml.etree.ElementTree as ET

REQUIRED_CLASSES = ("GenerationSmokeTest", "ChatUiE2eTest", "ComposeHostE2eTest", "ModelImportE2eTest", "NativeLifetimeTest", "ModelManagerE2eTest", "PromptEncodingNativeTest", "InferenceSettingsE2eTest", "NativeSettingsTest", "ToolConsentE2eTest", "ToolChatE2eTest", "RecordingE2eTest", "HistorySchemaTest", "HistoryRepositoryTest", "HistoryPersistenceE2eTest", "HistoryNavigationE2eTest", "HistoryDeletionE2eTest", "HistoryJourneyE2eTest", "FlashlightCameraManagerTest")
MIN_TESTS = 46


def verify(directory: Path) -> tuple[int, int, int]:
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
        raise ValueError("No executed Android JUnit testcases found (instrumentation may have been skipped)")

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
        name for name in REQUIRED_CLASSES
        if not any(name in class_name for class_name in classes)
    ]
    if len(cases) < MIN_TESTS or failed or skipped or missing:
        raise ValueError(
            f"Android E2E gate failed: tests={len(cases)}, failed={failed}, "
            f"skipped={skipped}, missing_required={missing}"
        )
    return len(cases), failed, skipped


def self_test() -> None:
    """Prove this gate rejects failing XML and the silently-empty test case."""
    with tempfile.TemporaryDirectory() as temp:
        directory = Path(temp)
        root = ET.Element("testsuite")
        for i in range(MIN_TESTS):
            name = REQUIRED_CLASSES[i % len(REQUIRED_CLASSES)]
            ET.SubElement(root, "testcase", classname=f"com.notcherry.{name}", name=f"test{i}")
        path = directory / "TEST-negative-control.xml"
        ET.ElementTree(root).write(path, encoding="utf-8")
        assert verify(directory) == (MIN_TESTS, 0, 0)

        root.find("testcase").append(ET.Element("failure", message="intentional regression"))
        ET.ElementTree(root).write(path, encoding="utf-8")
        try:
            verify(directory)
        except ValueError:
            pass
        else:
            raise AssertionError("Gate accepted a failing instrumentation test")

        path.unlink()
        try:
            verify(directory)
        except ValueError:
            pass
        else:
            raise AssertionError("Gate accepted an empty instrumentation run")
    print("Negative controls passed: failing and zero-test XML were rejected.")


def main() -> None:
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        self_test()
        return
    directory = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
        "app/build/outputs/androidTest-results/connected"
    )
    try:
        tests, failed, skipped = verify(directory)
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as output:
                output.write(f"### Android emulator tests: FAILED\n\n{exc}\n")
        sys.exit(1)

    line = f"Android emulator connected tests: {tests} executed, {failed} failed, {skipped} skipped"
    print(line)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write(f"### Android emulator tests: PASSED\n\n{line}\n")


if __name__ == "__main__":
    main()
