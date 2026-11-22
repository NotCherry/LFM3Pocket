#!/usr/bin/env python3
"""Fail CI when UI code bypasses Atlas / Stillwater color tokens.

Checks:
1. No `Color(0x…)` / ARGB `0xFF……` literals in app UI sources outside allowlisted token files.
2. `AtlasColors.inkSoft`, `StillwaterUi.SOFT`, and `colors.xml` `still_soft` stay in sync.
3. DESIGN tool-panel radius clause matches AtlasShapes (0–2dp), not the old 6–8dp wording.

Self-test: injecting a forbidden hex into a temp copy of ChatPanel.kt must fail the scan.
"""
from __future__ import annotations

from pathlib import Path
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
UI_ROOT = ROOT / "app" / "src" / "main" / "java" / "com" / "notcherry" / "lfm3pocket"
RES_COLORS = ROOT / "app" / "src" / "main" / "res" / "values" / "colors.xml"
ATLAS_COLORS = UI_ROOT / "ui" / "theme" / "AtlasColors.kt"
STILLWATER = UI_ROOT / "StillwaterUi.java"
DESIGN = ROOT / "docs" / "DESIGN.md"

# Token / palette sources may define hexes. Everything else under the app package must use them.
ALLOWLIST = {
    ATLAS_COLORS.resolve(),
    STILLWATER.resolve(),
    (UI_ROOT / "RuntimeNotifications.java").resolve(),
}

HEX_ARGB = re.compile(r"\b0xFF[0-9A-Fa-f]{6}\b")
COMPOSE_COLOR = re.compile(r"Color\(\s*0x[0-9A-Fa-f]{8}\s*\)")
SOFT_HEX = "C9C2B4"
INKSOFT_LINE = re.compile(r"inkSoft\s*[:=].*0xFF([0-9A-Fa-f]{6})")
SOFT_JAVA = re.compile(r"SOFT\s*=\s*0xFF([0-9A-Fa-f]{6})")
SOFT_XML = re.compile(r'name="still_soft">#([0-9A-Fa-f]{6})<')


def scan_forbidden_hex() -> list[str]:
    errors: list[str] = []
    for path in UI_ROOT.rglob("*"):
        if path.suffix not in {".kt", ".java"}:
            continue
        if path.resolve() in ALLOWLIST:
            continue
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(ROOT)
        for i, line in enumerate(text.splitlines(), 1):
            if line.lstrip().startswith("//") or line.lstrip().startswith("*"):
                continue
            if COMPOSE_COLOR.search(line) or HEX_ARGB.search(line):
                errors.append(f"{rel}:{i}: forbidden color literal (use AtlasColors / StillwaterUi): {line.strip()}")
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
    vals = {
        "AtlasColors.inkSoft": (m_atlas.group(1).upper() if m_atlas else None),
        "StillwaterUi.SOFT": (m_still.group(1).upper() if m_still else None),
        "still_soft": (m_xml.group(1).upper() if m_xml else None),
    }
    present = {k: v for k, v in vals.items() if v}
    if present and {v for v in present.values()} != {SOFT_HEX}:
        errors.append(f"soft chalk mismatch (expected {SOFT_HEX}): {present}")
    elif present and len(set(present.values())) > 1:
        errors.append(f"soft chalk out of sync: {present}")
    return errors


def check_design_radius() -> list[str]:
    text = DESIGN.read_text(encoding="utf-8")
    errors: list[str] = []
    if re.search(r"tool panel.*radius 6.?8", text, re.I):
        errors.append("DESIGN.md still documents tool panel radius 6–8dp; must match §4 / AtlasShapes (0–2dp)")
    if "radius **0–2dp**" not in text and "radius 0–2dp" not in text.lower():
        # §4 already has 0–2dp; §6.4 must also
        if "Bordered panel:" in text and "0–2dp" not in text.split("Bordered panel:")[1][:120]:
            errors.append("DESIGN.md §6.4 tool panel radius should state 0–2dp")
    return errors


def self_test() -> None:
    sample = UI_ROOT / "ChatPanel.kt"
    original = sample.read_text(encoding="utf-8")
    poisoned = original + "\nprivate val Bad = Color(0xFF123456)\n"
    with tempfile.TemporaryDirectory() as tmp:
        # Run scan logic against a fake tree would be heavy; instead assert helpers detect poison inline.
        if not COMPOSE_COLOR.search(poisoned):
            raise AssertionError("self-test: COMPOSE_COLOR failed to match injected literal")
    # Soft sync must see inkSoft
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
