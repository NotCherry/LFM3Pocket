#!/usr/bin/env python3
"""Inspect effective kernel compiler commands, not merely the CMake source setting."""
import json
from pathlib import Path
import re
import shlex
import sys


def verify(commands):
    kernels = 0
    for row in commands:
        if "/ggml/src/ggml-cpu/" not in row.get("file", "").replace("\\", "/"):
            continue
        kernels += 1
        flags = row.get("arguments") or shlex.split(row["command"])
        levels = [flag for flag in flags if re.fullmatch(r"-O(?:[0-3sgz]|fast)", flag)]
        if not levels or levels[-1] not in ("-O2", "-O3", "-Ofast"):
            raise ValueError(f"CPU kernel lacks effective optimization: {row['file']} ({levels})")
    if not kernels:
        raise ValueError("No ggml CPU kernel compiler commands were inspected")
    return kernels


def self_test():
    source = "/vendor/ggml/src/ggml-cpu/ops.cpp"
    assert verify([{"file": source, "command": "clang++ -g -O0 -O2 -c ops.cpp"}]) == 1
    assert verify([{"file": source, "arguments": ["clang++", "-g", "-O3"]}]) == 1
    for flags in ([], ["-g"], ["-O2", "-O0"], ["-O1"], ["-Og"]):
        try:
            verify([{"file": source, "arguments": ["clang++", *flags]}])
        except ValueError:
            pass
        else:
            raise AssertionError(f"Accepted unoptimized kernel: {flags}")
    try:
        verify([{"file": "/app/native.cpp", "arguments": ["clang++", "-O2"]}])
    except ValueError:
        pass
    else:
        raise AssertionError("Accepted missing kernel inventory")
    print("Native optimization positive/negative controls passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    else:
        root = Path(sys.argv[1] if len(sys.argv) > 1 else "app/.cxx")
        rows = []
        for path in root.rglob("compile_commands.json"):
            rows.extend(json.loads(path.read_text()))
        try:
            print(f"Optimized ggml CPU compiler commands checked: {verify(rows)}")
        except ValueError as failure:
            sys.exit(str(failure))
