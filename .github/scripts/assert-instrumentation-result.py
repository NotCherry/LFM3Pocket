#!/usr/bin/env python3
"""Require one successful am-instrument summary; Android may exit 0 on a test failure."""
from pathlib import Path
import re
import sys
import tempfile


def verify(path: Path, expected: int = 1) -> None:
    text = path.read_text()
    counts = [int(n) for n in re.findall(r"OK \((\d+) tests?\)", text)]
    if counts != [expected] or any(marker in text for marker in (
        "FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed", "INSTRUMENTATION_ABORTED",
    )):
        raise ValueError(f"Instrumentation did not pass exactly {expected} test(s): {path}")


def self_test() -> None:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "instrumentation.txt"
        path.write_text("OK (1 test)\nINSTRUMENTATION_CODE: -1\n")
        verify(path)
        path.write_text("OK (2 tests)\nINSTRUMENTATION_CODE: -1\n")
        verify(path, expected=2)
        for text in ("", "FAILURES!!!", "OK (0 tests)", "OK (1 test)\nProcess crashed", "OK (1 test)\nOK (1 test)"):
            path.write_text(text)
            try:
                verify(path)
            except ValueError:
                continue
            raise AssertionError(f"Accepted invalid instrumentation output: {text!r}")
        path.write_text("OK (1 test)\n")
        try:
            verify(path, expected=2)
        except ValueError:
            pass
        else:
            raise AssertionError("Accepted wrong test count")
    print("Instrumentation output gate: positive and negative controls passed.")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 3:
        verify(Path(sys.argv[1]), expected=int(sys.argv[2]))
    else:
        verify(Path(sys.argv[1]))
