#!/usr/bin/env python3
"""Fail when an APK was packaged without libhanami_dizione.so for the expected ABI(s).
Used by Stage 1 CI, release.yml, and assert --self-test negative controls."""
from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

LIB_NAME = "libhanami_dizione.so"


def expected_entries(abis: list[str]) -> list[str]:
    return [f"lib/{abi}/{LIB_NAME}" for abi in abis]


def assert_apk(apk_path: Path, abis: list[str]) -> list[str]:
    if not apk_path.is_file() or apk_path.stat().st_size <= 0:
        raise ValueError(f"APK missing or empty: {apk_path}")
    entries = expected_entries(abis)
    found: list[str] = []
    try:
        archive = zipfile.ZipFile(apk_path)
    except zipfile.BadZipFile as exc:
        raise ValueError(f"Not a valid APK/ZIP: {apk_path}") from exc
    with archive:
        names = set(archive.namelist())
        for entry in entries:
            if entry not in names:
                raise ValueError(
                    f"APK lacks {entry}. Run scripts/fetch-voca.sh, ensure Rust + NDK are "
                    "available, and rebuild with HANAMI_REQUIRE_DIZIONE=1 for release/CI phone builds."
                )
            info = archive.getinfo(entry)
            if info.file_size <= 0:
                raise ValueError(f"APK entry {entry} is empty")
            found.append(entry)
    return found


def self_test() -> None:
    import os
    import subprocess

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        apk = tmp_path / "empty.apk"
        apk.write_bytes(b"not-an-apk")
        try:
            assert_apk(apk, ["arm64-v8a"])
        except ValueError as exc:
            if "Not a valid APK/ZIP" not in str(exc):
                raise AssertionError(f"Unexpected error: {exc}") from exc
        else:
            raise AssertionError("Accepted a non-APK file")

        missing = tmp_path / "no-jni.apk"
        with zipfile.ZipFile(missing, "w") as archive:
            archive.writestr("lib/arm64-v8a/libother.so", b"\x00" * 8)
        try:
            assert_apk(missing, ["arm64-v8a"])
        except ValueError as exc:
            if LIB_NAME not in str(exc):
                raise AssertionError(f"Unexpected error: {exc}") from exc
        else:
            raise AssertionError("Accepted APK without dizione JNI")

        good = tmp_path / "with-jni.apk"
        with zipfile.ZipFile(good, "w") as archive:
            archive.writestr("lib/arm64-v8a/libhanami_dizione.so", b"\x7fELF" + b"\x00" * 60)
            archive.writestr("lib/x86_64/libhanami_dizione.so", b"\x7fELF" + b"\x00" * 60)
        found = assert_apk(good, ["arm64-v8a", "x86_64"])
        assert len(found) == 2

        # Failure path for build-dizione-jni.sh: require flag must exit non-zero when cargo is absent.
        root = Path(__file__).resolve().parents[2]
        script = root / "scripts" / "build-dizione-jni.sh"
        fixture = tmp_path / "fixture"
        (fixture / "vendor/voca/crates/dizione").mkdir(parents=True)
        (fixture / "scripts").mkdir()
        shutil.copy2(script, fixture / "scripts" / "build-dizione-jni.sh")
        fake_bin = tmp_path / "nobin"
        fake_bin.mkdir()
        env = os.environ.copy()
        env["HANAMI_REQUIRE_DIZIONE"] = "1"
        env["HANAMI_BUILD_ABIS"] = "arm64-v8a"
        env["HANAMI_SKIP_VOCA_FETCH"] = "1"
        env.pop("ANDROID_NDK_HOME", None)
        env.pop("ANDROID_NDK_ROOT", None)
        env.pop("ANDROID_HOME", None)
        env["PATH"] = f"{fake_bin}:/usr/bin:/bin"
        completed = subprocess.run(
            ["bash", str(fixture / "scripts" / "build-dizione-jni.sh")],
            cwd=fixture,
            env=env,
            capture_output=True,
            text=True,
        )
        if completed.returncode == 0:
            raise AssertionError("build-dizione-jni.sh succeeded without cargo/NDK while required")
        combined = completed.stderr + completed.stdout
        if "HANAMI_REQUIRE_DIZIONE" not in combined and "cargo not installed" not in combined:
            raise AssertionError(
                "build-dizione-jni.sh failed without a clear require/cargo message:\n" + combined
            )

    print("dizione JNI APK positive/negative controls passed")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="?", help="Path to APK")
    parser.add_argument(
        "--abis",
        default="arm64-v8a",
        help="Comma-separated ABI folder names under lib/ (default: arm64-v8a)",
    )
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        self_test()
        return 0
    if not args.apk:
        parser.error("apk path is required unless --self-test is set")
    abis = [part.strip() for part in args.abis.split(",") if part.strip()]
    if not abis:
        parser.error("--abis must list at least one ABI")
    found = assert_apk(Path(args.apk), abis)
    print(f"dizione JNI present: {', '.join(found)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except ValueError as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        raise SystemExit(1)