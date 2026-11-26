#!/usr/bin/env python3
"""Verify Android 15-17 APK compatibility invariants (ADR 0026).

Checks:
1. Every 64-bit native library (lib/arm64-v8a/*.so, lib/x86_64/*.so) has:
   - ELF64 PT_LOAD p_align >= 16384 (0x4000) for 16 KB page-size devices
   - ZIP STORED (uncompressed) entry with 16 KB-aligned data offset
2. When --require-v2-v3 is set, APK Signing Block contains both Signature Scheme v2
   (0x7109871a) and v3 (0xf05368c0).
3. When aapt2 is available (or --require-aapt2 is set), manifest does not set
   extractNativeLibs=true and does not declare required camera/microphone features.

Run with --self-test to prove both positive and negative controls without external tools.
"""
from __future__ import annotations

import argparse
import glob
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

PAGE_SIZE_16K = 16384
ELF_MAGIC = b"\x7fELF"
ELFCLASS64 = 2
PT_LOAD = 1
TARGET_64BIT_ABIS = ("arm64-v8a", "x86_64")

SIG_BLOCK_MAGIC = b"APK Sig Block 42"
SIG_V2_ID = 0x7109871A
SIG_V3_ID = 0xF05368C0
SIG_V31_ID = 0x1B93AD61


def parse_elf64_pt_load_alignments(data: bytes, name: str) -> list[int]:
    """Return p_align values for every PT_LOAD segment in an ELF64 binary."""
    if len(data) < 64 or data[:4] != ELF_MAGIC:
        raise ValueError(f"{name}: not a valid ELF binary")
    elf_class = data[4]
    if elf_class != ELFCLASS64:
        raise ValueError(f"{name}: expected ELF64 (class=2), got class={elf_class}")
    ei_data = data[5]
    endian = "<" if ei_data == 1 else ">"
    e_phoff = struct.unpack_from(f"{endian}Q", data, 32)[0]
    e_phentsize = struct.unpack_from(f"{endian}H", data, 54)[0]
    e_phnum = struct.unpack_from(f"{endian}H", data, 56)[0]
    if e_phoff == 0 or e_phnum == 0 or e_phentsize < 56:
        raise ValueError(f"{name}: missing ELF64 program headers")

    alignments: list[int] = []
    for idx in range(e_phnum):
        off = e_phoff + idx * e_phentsize
        if off + 56 > len(data):
            raise ValueError(f"{name}: truncated program header #{idx}")
        p_type = struct.unpack_from(f"{endian}I", data, off)[0]
        if p_type == PT_LOAD:
            p_align = struct.unpack_from(f"{endian}Q", data, off + 48)[0]
            alignments.append(p_align)
    if not alignments:
        raise ValueError(f"{name}: no PT_LOAD segments found")
    return alignments


def zip_entry_data_offset(apk_fp, info: zipfile.ZipInfo) -> int:
    apk_fp.seek(info.header_offset)
    local_header = apk_fp.read(30)
    if len(local_header) != 30 or local_header[:4] != b"PK\x03\x04":
        raise ValueError(f"{info.filename}: invalid ZIP local file header")
    fn_len, extra_len = struct.unpack_from("<HH", local_header, 26)
    return info.header_offset + 30 + fn_len + extra_len


def read_apk_sig_block_ids(apk_path: Path) -> set[int]:
    """Read signature scheme block IDs from the APK Signing Block (if present)."""
    with apk_path.open("rb") as fp:
        fp.seek(0, os.SEEK_END)
        file_size = fp.tell()
        search_len = min(file_size, 65557)
        fp.seek(file_size - search_len)
        tail = fp.read(search_len)
        eocd_pos = tail.rfind(b"PK\x05\x06")
        if eocd_pos < 0 or eocd_pos + 22 > len(tail):
            return set()
        cd_offset = struct.unpack_from("<I", tail, eocd_pos + 16)[0]
        if cd_offset < 32:
            return set()
        fp.seek(cd_offset - 16)
        if fp.read(16) != SIG_BLOCK_MAGIC:
            return set()
        fp.seek(cd_offset - 24)
        block_size = struct.unpack("<Q", fp.read(8))[0]
        block_start = cd_offset - block_size - 8
        if block_start < 0:
            return set()
        pos = block_start + 8
        end = cd_offset - 24
        fp.seek(pos)
        ids: set[int] = set()
        while pos + 12 <= end:
            pair_len = struct.unpack("<Q", fp.read(8))[0]
            if pair_len < 4 or pos + 8 + pair_len > end:
                break
            pair_id = struct.unpack("<I", fp.read(4))[0]
            ids.add(pair_id)
            pos += 8 + pair_len
            fp.seek(pos)
        return ids


def find_aapt2(explicit: str | None) -> str | None:
    if explicit:
        return explicit if Path(explicit).is_file() else None
    found = shutil.which("aapt2")
    if found:
        return found
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if android_home:
        candidates = sorted(glob.glob(os.path.join(android_home, "build-tools", "*", "aapt2")))
        if candidates:
            return candidates[-1]
    return None


def verify_manifest_with_aapt2(apk_path: Path, aapt2_bin: str) -> list[str]:
    errors: list[str] = []
    xml_out = subprocess.check_output(
        [aapt2_bin, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk_path)],
        text=True,
        stderr=subprocess.STDOUT,
    )
    if "extractNativeLibs" in xml_out and "extractNativeLibs(0x010104ea)=true" in xml_out:
        errors.append("AndroidManifest.xml sets android:extractNativeLibs=true (expected false for 16 KB alignment)")

    badging_out = subprocess.check_output(
        [aapt2_bin, "dump", "badging", str(apk_path)],
        text=True,
        stderr=subprocess.STDOUT,
    )
    for line in badging_out.splitlines():
        stripped = line.strip()
        if stripped in (
            "uses-feature: name='android.hardware.microphone'",
            "uses-feature: name='android.hardware.camera'",
        ):
            errors.append(f"Manifest implies required hardware feature: {stripped} (declare android:required=\"false\")")
    return errors


def verify_apk(
    apk_path: Path,
    *,
    require_v2_v3: bool = False,
    aapt2_bin: str | None = None,
    require_aapt2: bool = False,
) -> tuple[list[str], dict[str, int]]:
    errors: list[str] = []
    checked_sos = 0
    if not apk_path.is_file():
        return [f"APK not found: {apk_path}"], {"checked_sos": 0}

    with zipfile.ZipFile(apk_path, "r") as zf, apk_path.open("rb") as raw_fp:
        for info in zf.infolist():
            if not info.filename.startswith("lib/") or not info.filename.endswith(".so"):
                continue
            parts = info.filename.split("/")
            if len(parts) != 3 or parts[1] not in TARGET_64BIT_ABIS:
                continue
            checked_sos += 1

            if info.compress_type != zipfile.ZIP_STORED:
                errors.append(
                    f"{info.filename}: compressed in APK (method={info.compress_type}); "
                    "expected uncompressed STORED (useLegacyPackaging false)"
                )
            else:
                offset = zip_entry_data_offset(raw_fp, info)
                if offset % PAGE_SIZE_16K != 0:
                    errors.append(
                        f"{info.filename}: ZIP data offset {offset} is not 16 KB aligned "
                        f"(offset % 16384 = {offset % PAGE_SIZE_16K})"
                    )

            data = zf.read(info)
            try:
                aligns = parse_elf64_pt_load_alignments(data, info.filename)
            except ValueError as exc:
                errors.append(str(exc))
                continue
            bad = [a for a in aligns if a < PAGE_SIZE_16K]
            if bad:
                hex_list = ", ".join(hex(a) for a in bad)
                errors.append(
                    f"{info.filename}: ELF PT_LOAD p_align < 0x4000 (found {hex_list}); "
                    "not 16 KB page-size compatible"
                )

    if checked_sos == 0:
        errors.append("No 64-bit native .so libraries found in APK (expected lib/arm64-v8a or lib/x86_64)")

    if require_v2_v3:
        sig_ids = read_apk_sig_block_ids(apk_path)
        if SIG_V2_ID not in sig_ids:
            errors.append("APK Signing Block missing APK Signature Scheme v2 (0x7109871a)")
        if SIG_V3_ID not in sig_ids and SIG_V31_ID not in sig_ids:
            errors.append("APK Signing Block missing APK Signature Scheme v3 (0xf05368c0)")

    resolved_aapt2 = find_aapt2(aapt2_bin)
    if resolved_aapt2:
        try:
            errors.extend(verify_manifest_with_aapt2(apk_path, resolved_aapt2))
        except subprocess.CalledProcessError as exc:
            errors.append(f"aapt2 verification failed: {exc.output}")
    elif require_aapt2:
        errors.append("aapt2 requested (--require-aapt2) but not found on PATH or ANDROID_HOME")

    return errors, {"checked_sos": checked_sos}


def _make_synthetic_elf64(p_align: int) -> bytes:
    """Build a minimal valid ELF64 binary with one PT_LOAD segment at p_align."""
    ehdr = bytearray(64)
    ehdr[0:4] = ELF_MAGIC
    ehdr[4] = ELFCLASS64
    ehdr[5] = 1  # little-endian
    ehdr[6] = 1  # version
    struct.pack_into("<H", ehdr, 16, 3)   # ET_DYN
    struct.pack_into("<H", ehdr, 18, 183) # EM_AARCH64
    struct.pack_into("<I", ehdr, 20, 1)
    struct.pack_into("<Q", ehdr, 32, 64)  # e_phoff = 64
    struct.pack_into("<H", ehdr, 52, 64)  # e_ehsize
    struct.pack_into("<H", ehdr, 54, 56)  # e_phentsize
    struct.pack_into("<H", ehdr, 56, 1)   # e_phnum

    phdr = bytearray(56)
    struct.pack_into("<I", phdr, 0, PT_LOAD)
    struct.pack_into("<I", phdr, 4, 5)        # PF_R | PF_X
    struct.pack_into("<Q", phdr, 48, p_align) # p_align
    return bytes(ehdr + phdr)


def _write_synthetic_apk(
    path: Path,
    *,
    p_align: int = PAGE_SIZE_16K,
    compress: bool = False,
    align_zip_16k: bool = True,
    sig_ids: tuple[int, ...] = (),
) -> None:
    elf = _make_synthetic_elf64(p_align)
    with zipfile.ZipFile(path, "w") as zf:
        entry_name = "lib/arm64-v8a/libtest.so"
        zi = zipfile.ZipInfo(entry_name)
        zi.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
        if not compress and align_zip_16k:
            # Header starts at offset 0: 30 + len(entry_name) + extra_len == 16384
            base = 30 + len(entry_name.encode("utf-8"))
            pad = (PAGE_SIZE_16K - (base % PAGE_SIZE_16K)) % PAGE_SIZE_16K
            zi.extra = b"\x00" * pad
        zf.writestr(zi, elf)

    if sig_ids:
        raw = path.read_bytes()
        eocd_pos = raw.rfind(b"PK\x05\x06")
        cd_offset = struct.unpack_from("<I", raw, eocd_pos + 16)[0]
        pairs = b"".join(struct.pack("<QI", 8, sid) + b"\x01\x02\x03\x04" for sid in sig_ids)
        block_size = len(pairs) + 24
        sig_block = struct.pack("<Q", block_size) + pairs + struct.pack("<Q", block_size) + SIG_BLOCK_MAGIC
        new_cd_offset = cd_offset + len(sig_block)
        patched = bytearray(raw[:cd_offset] + sig_block + raw[cd_offset:])
        new_eocd_pos = eocd_pos + len(sig_block)
        struct.pack_into("<I", patched, new_eocd_pos + 16, new_cd_offset)
        path.write_bytes(bytes(patched))


def run_self_test() -> None:
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)

        # 1. Valid 16 KB aligned + STORED + v2+v3 signed APK -> PASS
        good = root / "good.apk"
        _write_synthetic_apk(good, p_align=16384, compress=False, align_zip_16k=True, sig_ids=(SIG_V2_ID, SIG_V3_ID))
        errs, stats = verify_apk(good, require_v2_v3=True, aapt2_bin="/nonexistent")
        assert not errs, f"Expected valid APK to pass, got: {errs}"
        assert stats["checked_sos"] == 1

        # 2. 4 KB ELF PT_LOAD alignment -> REJECT
        bad_elf = root / "bad_elf.apk"
        _write_synthetic_apk(bad_elf, p_align=4096, compress=False, align_zip_16k=True)
        errs, _ = verify_apk(bad_elf, aapt2_bin="/nonexistent")
        assert any("PT_LOAD" in e for e in errs), f"Expected PT_LOAD failure, got: {errs}"

        # 3. Compressed .so (useLegacyPackaging true) -> REJECT
        bad_comp = root / "bad_comp.apk"
        _write_synthetic_apk(bad_comp, p_align=16384, compress=True, align_zip_16k=False)
        errs, _ = verify_apk(bad_comp, aapt2_bin="/nonexistent")
        assert any("compressed" in e for e in errs), f"Expected compressed failure, got: {errs}"

        # 4. Uncompressed .so but non-16 KB ZIP offset -> REJECT
        bad_off = root / "bad_off.apk"
        _write_synthetic_apk(bad_off, p_align=16384, compress=False, align_zip_16k=False)
        errs, _ = verify_apk(bad_off, aapt2_bin="/nonexistent")
        assert any("ZIP data offset" in e for e in errs), f"Expected ZIP offset failure, got: {errs}"

        # 5. Missing v3 signature when --require-v2-v3 -> REJECT
        v2_only = root / "v2_only.apk"
        _write_synthetic_apk(v2_only, p_align=16384, compress=False, align_zip_16k=True, sig_ids=(SIG_V2_ID,))
        errs, _ = verify_apk(v2_only, require_v2_v3=True, aapt2_bin="/nonexistent")
        assert any("Scheme v3" in e for e in errs), f"Expected missing v3 failure, got: {errs}"

    print("assert-apk-android-compat.py self-test passed (1 positive, 4 negative controls).")


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Android 15-17 APK compatibility invariants (ADR 0026)")
    parser.add_argument("apk", nargs="?", help="Path to APK file to verify")
    parser.add_argument("--require-v2-v3", action="store_true", help="Require APK Signature Scheme v2 and v3")
    parser.add_argument("--aapt2", default=None, help="Explicit path to aapt2 binary")
    parser.add_argument("--require-aapt2", action="store_true", help="Fail if aapt2 is unavailable")
    parser.add_argument("--self-test", action="store_true", help="Run built-in positive/negative self-tests")
    args = parser.parse_args()

    if args.self_test:
        run_self_test()
        return 0
    if not args.apk:
        parser.error("Provide an APK path or --self-test")

    errors, stats = verify_apk(
        Path(args.apk),
        require_v2_v3=args.require_v2_v3,
        aapt2_bin=args.aapt2,
        require_aapt2=args.require_aapt2,
    )
    if errors:
        print(f"Android 15-17 APK compatibility check FAILED for {args.apk}:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1
    print(
        f"Android 15-17 APK compatibility check PASSED for {args.apk} "
        f"({stats['checked_sos']} 64-bit native lib(s) verified 16 KB ELF + ZIP aligned)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
