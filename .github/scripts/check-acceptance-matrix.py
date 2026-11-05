#!/usr/bin/env python3
"""Reject missing, duplicate or unowned AT-01..AT-09 acceptance rows in CI."""
from __future__ import annotations

from pathlib import Path
import re
import sys

EXPECTED = {f"AT-{n:02d}" for n in range(1, 10)}
STATUSES = {"MISSING", "PARTIAL", "VERIFIED"}


def verify(text: str) -> None:
    rows: dict[str, list[str]] = {}
    for raw in text.splitlines():
        if not re.match(r"^\\| AT-\\d{2} \\|", raw):
            continue
        cols = [item.strip() for item in raw.strip().strip("|").split("|")]
        if len(cols) != 6:
            raise ValueError(f"Acceptance row must have six columns: {raw}")
        key = cols[0]
        if key in rows:
            raise ValueError(f"Duplicate acceptance identifier: {key}")
        rows[key] = cols

    if set(rows) != EXPECTED:
        raise ValueError(
            f"Acceptance IDs differ: missing={sorted(EXPECTED - set(rows))}, "
            f"unexpected={sorted(set(rows) - EXPECTED)}"
        )
    for key, cols in rows.items():
        _, requirement, owners, tests, hardware, status = cols
        if len(requirement) < 20:
            raise ValueError(f"{key}: missing concrete acceptance criterion")
        if not re.search(r"#\\d+", owners):
            raise ValueError(f"{key}: no owning implementation issue")
        if not any(label in tests for label in ("Existing:", "Planned:", "Pending:")):
            raise ValueError(f"{key}: no explicit existing/planned automated test")
        if "ARM64" not in hardware and "device" not in hardware.lower():
            raise ValueError(f"{key}: no device-level verification criterion")
        if status not in STATUSES:
            raise ValueError(f"{key}: invalid verification status {status}")
        if status == "VERIFIED" and ("MISSING" in tests or "planned:" in tests.lower()):
            raise ValueError(f"{key}: a planned/unverified test cannot be marked VERIFIED")


def main() -> None:
    source = Path("docs/ACCEPTANCE_MATRIX.md")
    text = source.read_text(encoding="utf-8")
    verify(text)
    # Negative control: omitting one acceptance criterion must make the gate fail.
    missing = "\\n".join(
        line for line in text.splitlines() if not line.startswith("| AT-09 |")
    )
    try:
        verify(missing)
    except ValueError:
        pass
    else:
        raise AssertionError("Matrix gate accepted missing AT-09")
    print("Acceptance matrix check passed: AT-01..AT-09 unique and owned; negative control rejected.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
