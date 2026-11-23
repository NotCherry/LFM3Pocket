#!/usr/bin/env python3
"""Fail CI when UI code bypasses Atlas / Stillwater color tokens.

Also keeps DESIGN soft-chalk / radius clauses honest for audit follow-ups.

Checks:
1. No `Color(0x…)` / 8-digit ARGB hex literals in app sources outside allowlisted
   token files (case-insensitive; any alpha). Inline escape: `ui-tokens: allow`.
2. `AtlasColors.inkSoft`, `StillwaterUi.SOFT`, and `colors.xml` `still_soft` stay in sync.
3. DESIGN tool-panel radius clause matches AtlasShapes (0–2dp), not the old 6–8dp wording.

Self-test: write a poisoned ChatPanel copy under a temp tree and assert
`scan_forbidden_hex` reports it.
"""
from __future__ import annotations

from pathlib import Path
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
APP_JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "notcherry" / "lfm3pocket"
RES_COLORS = ROOT / "app" / "src" / "main" / "res" / "values" / "colors.xml"
ATLAS_COLORS = APP_JAVA / "ui" / "theme" / "AtlasColors.kt"
STILLWATER = APP_JAVA / "StillwaterUi.java"
DESIGN = ROOT / "docs" / "DESIGN.md"

ALLOWLIST = {
    ATLAS_COLORS.resolve(),
    STILLWATER.resolve(),
    (APP_JAVA / "RuntimeNotifications.java").resolve(),
}

# Any 8-digit hex (ARGB), case-insensitive — covers 0xff… and non-FF alphas like 0x66E6C27A.
HEX_ARGB = re.compile(r"\b0x[0-9A-Fa-f]{8}\b", re.I)
COMPOSE_COLOR = re.compile(r"Color\(\s*0x[0-9A-Fa-f]{8}\s*\)", re.I)
ALLOW_MARK = re.compile(r"ui-tokens:\s*allow", re.I)
SOFT_HEX = "C9C2B4"
INKSOFT_LINE = re.compile(r"inkSoft\s*[:=].*0x[Ff]{2}([0-9A-Fa-f]{6})")
SOFT_JAVA = re.compile(r"SOFT\s*=\s*0x[Ff]{2}([0-9A-Fa-f]{6})")
SOFT_XML = re.compile(r'name="still_soft">#([0-9A-Fa-f]{6})<')


def scan_forbidden_hex(root: Path = APP_JAVA, repo_root: Path = ROOT) -> list[str]:
    """Scan Kotlin/Java under `root` for color hex literals outside the allowlist."""
    errors: list[str] = []
    for path in root.rglob("*"):
        if path.suffix not in {".kt", ".java"}:
            continue
        if path.resolve() in ALLOWLIST:
            continue
        text = path.read_text(encoding="utf-8")
        try:
            rel = path.relative_to(repo_root)
        except ValueError:
            rel = path
        for i, line in enumerate(text.splitlines(), 1):
            stripped = line.lstrip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            if ALLOW_MARK.search(line):
                continue
            if COMPOSE_COLOR.search(line) or HEX_ARGB.search(line):
                errors.append(
                    f"{rel}:{i}: forbidden color literal "
                    f"(use AtlasColors / StillwaterUi, or mark `ui-tokens: allow`): {line.strip()}"
                )
    return errors


def check_soft_sync() -> list[str]:
    errors: list[str] = []
    atlas = ATLAS_COLORS.read_text(encoding="utf-8")
    still = STILLWATER.read_text(encoding="utf-8")
    colors = RES_COLORS.read_text(encoding="utf-8")
    m_atlas = INKSOFT_LINE.search(atlas)
    m_still = SOFT_JAVA.search(still)
    m_xml = SOFT_XML.search(colors)
    if not m_atlas:
        errors.append("AtlasColors.kt missing inkSoft token")
    if not m_still:
        errors.append("StillwaterUi.java missing SOFT constant")
    if not m_xml:
        errors.append("colors.xml missing still_soft")
    present = {
        "AtlasColors.inkSoft": (m_atlas.group(1).upper() if m_atlas else None),
        "StillwaterUi.SOFT": (m_still.group(1).upper() if m_still else None),
        "still_soft": (m_xml.group(1).upper() if m_xml else None),
    }
    present = {k: v for k, v in present.items() if v}
    if not present:
        return errors
    unique = set(present.values())
    if len(unique) > 1:
        errors.append(f"soft chalk out of sync: {present}")
    elif unique != {SOFT_HEX}:
        errors.append(f"soft chalk mismatch (expected {SOFT_HEX}): {present}")
    return errors


def check_design_radius() -> list[str]:
    text = DESIGN.read_text(encoding="utf-8")
    errors: list[str] = []
    if re.search(r"tool panel.*radius 6.?8", text, re.I):
        errors.append(
            "DESIGN.md still documents tool panel radius 6–8dp; must match §4 / AtlasShapes (0–2dp)"
        )
    if "Bordered panel:" in text and "0–2dp" not in text.split("Bordered panel:")[1][:160]:
        errors.append("DESIGN.md §6.4 tool panel radius should state 0–2dp")
    return errors


def self_test() -> None:
    sample = APP_JAVA / "ChatPanel.kt"
    original = sample.read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        # Minimal tree mirroring package layout under a fake repo root.
        pkg = tmp_root / "app" / "src" / "main" / "java" / "com" / "notcherry" / "lfm3pocket"
        pkg.mkdir(parents=True)
        poisoned = pkg / "ChatPanel.kt"
        poisoned.write_text(original + "\nprivate val Bad = Color(0xFF123456)\n", encoding="utf-8")
        # Also cover lowercase / non-FF alpha without allow mark.
        (pkg / "Other.java").write_text(
            "class Other { int c = 0xffaabbcc; int a = 0x66E6C27A; }\n",
            encoding="utf-8",
        )
        found = scan_forbidden_hex(root=pkg, repo_root=tmp_root)
        if not any("ChatPanel.kt" in e and "0xFF123456" in e for e in found):
            raise AssertionError(f"self-test: poisoned ChatPanel not flagged: {found}")
        if not any("Other.java" in e and "0xffaabbcc" in e.lower() for e in found):
            raise AssertionError(f"self-test: lowercase hex not flagged: {found}")
        if not any("Other.java" in e and "0x66E6C27A" in e for e in found):
            raise AssertionError(f"self-test: non-FF alpha not flagged: {found}")
        # Allow mark must suppress.
        (pkg / "Allowed.java").write_text(
            "class Allowed { int c = 0xFF123456; // ui-tokens: allow\n}\n",
            encoding="utf-8",
        )
        found2 = scan_forbidden_hex(root=pkg, repo_root=tmp_root)
        if any("Allowed.java" in e for e in found2):
            raise AssertionError(f"self-test: allow mark ignored: {found2}")
    errs = check_soft_sync()
    if errs:
        raise AssertionError(f"self-test soft sync failed on clean tree: {errs}")


def main() -> int:
    self_test()
    errors = scan_forbidden_hex() + check_soft_sync() + check_design_radius()
    if errors:
        print("UI token check failed:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1
    print("UI token check OK (no forbidden hex; soft chalk synced; DESIGN radius aligned)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
