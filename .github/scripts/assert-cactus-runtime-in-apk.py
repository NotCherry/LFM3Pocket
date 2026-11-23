#!/usr/bin/env python3
"""Fail when a phone ARM64 APK was built without the linked Cactus Whistle runtime.

libcactus.a is statically linked into liblfm3pocket.so (there is no separate libcactus.so).
This guard extracts that shared library and requires cactus_load / cactus_transcribe symbols.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


REQUIRED_SYMBOLS = ("cactus_load", "cactus_transcribe")
LIB_PATH = "lib/arm64-v8a/liblfm3pocket.so"


def _nm_command() -> list[str] | None:
    for candidate in ("llvm-nm", "nm"):
        path = shutil.which(candidate)
        if path:
            return [path, "-D"]
    return None


def _readelf_command() -> list[str] | None:
    path = shutil.which("readelf")
    if path:
        return [path, "-Ws"]
    return None


def symbols_from_output(text: str) -> set[str]:
    found: set[str] = set()
    for line in text.splitlines():
        for name in REQUIRED_SYMBOLS:
            # Match bare symbol tokens (nm -D / readelf -Ws).
            parts = line.replace("@", " ").split()
            if name in parts:
                found.add(name)
    return found


def inspect_shared_object(so_path: Path) -> set[str]:
    commands: list[list[str]] = []
    nm = _nm_command()
    if nm:
        commands.append(nm + [str(so_path)])
    readelf = _readelf_command()
    if readelf:
        commands.append(readelf + [str(so_path)])
    if not commands:
        raise RuntimeError("Neither nm/llvm-nm nor readelf is available to inspect native symbols")

    errors: list[str] = []
    for cmd in commands:
        try:
            completed = subprocess.run(cmd, check=True, capture_output=True, text=True)
        except (OSError, subprocess.CalledProcessError) as exc:
            errors.append(f"{' '.join(cmd)}: {exc}")
            continue
        found = symbols_from_output(completed.stdout + "\n" + completed.stderr)
        if found:
            return found
        errors.append(f"{' '.join(cmd)}: no required cactus symbols in output")
    raise RuntimeError("Could not read cactus symbols from liblfm3pocket.so:\n" + "\n".join(errors))


def assert_apk(apk_path: Path) -> set[str]:
    if not apk_path.is_file() or apk_path.stat().st_size <= 0:
        raise ValueError(f"APK missing or empty: {apk_path}")
    with zipfile.ZipFile(apk_path) as archive:
        try:
            info = archive.getinfo(LIB_PATH)
        except KeyError as exc:
            raise ValueError(f"APK lacks {LIB_PATH}") from exc
        if info.file_size <= 0:
            raise ValueError(f"APK entry {LIB_PATH} is empty")
        with tempfile.TemporaryDirectory() as tmp:
            so_path = Path(tmp) / "liblfm3pocket.so"
            so_path.write_bytes(archive.read(LIB_PATH))
            found = inspect_shared_object(so_path)
    missing = [name for name in REQUIRED_SYMBOLS if name not in found]
    if missing:
        raise ValueError(
            "Cactus Whistle runtime is not linked into liblfm3pocket.so "
            f"(missing symbols: {', '.join(missing)}). "
            "Run scripts/fetch-cactus.sh and rebuild with HANAMI_REQUIRE_CACTUS=ON."
        )
    return found


def self_test() -> None:
    # Positive: synthetic nm output containing both symbols.
    sample = (
        "0000000000123456 T cactus_load\n"
        "0000000000123460 T cactus_transcribe\n"
        "0000000000123470 T Java_com_notcherry_lfm3pocket_CactusWhistleBridge_nativeProbe\n"
    )
    assert symbols_from_output(sample) == set(REQUIRED_SYMBOLS)

    # Negative: stub JNI only — no cactus_* C API symbols.
    stub = (
        "000000000024b80c T Java_com_notcherry_lfm3pocket_CactusWhistleBridge_nativeProbe\n"
        "000000000024b814 T Java_com_notcherry_lfm3pocket_CactusWhistleBridge_nativeLoad\n"
    )
    assert symbols_from_output(stub) == set()

    with tempfile.TemporaryDirectory() as tmp:
        apk = Path(tmp) / "empty.apk"
        apk.write_bytes(b"not-an-apk")
        try:
            assert_apk(apk)
        except (ValueError, zipfile.BadZipFile):
            pass
        else:
            raise AssertionError("Accepted a non-APK file")

        good = Path(tmp) / "with-lib.apk"
        with zipfile.ZipFile(good, "w") as archive:
            archive.writestr(LIB_PATH, b"\x00" * 64)
        # Missing nm symbols should fail after extract (binary is not a real ELF).
        try:
            assert_apk(good)
        except (ValueError, RuntimeError):
            pass
        else:
            raise AssertionError("Accepted APK without cactus symbols")

    print("Cactus runtime APK positive/negative controls passed")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="?", help="Path to phone ARM64 APK")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        self_test()
        return 0
    if not args.apk:
        parser.error("apk path is required unless --self-test is set")
    found = assert_apk(Path(args.apk))
    print(f"Cactus runtime symbols present in {LIB_PATH}: {', '.join(sorted(found))}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except (ValueError, RuntimeError) as failure:
        print(str(failure), file=sys.stderr)
        raise SystemExit(1)
