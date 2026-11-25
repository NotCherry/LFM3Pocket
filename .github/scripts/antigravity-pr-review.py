#!/usr/bin/env python3
"""Antigravity automated PR review & regression guard for Hanami (LFM3Pocket).

Runs deterministic high-signal architectural checks on every PR (zero secrets
required) and optionally appends an AI diff review via Gemini API when
`GEMINI_API_KEY` is configured in GitHub Actions secrets.

Checks enforced:
1. Inference thread isolation: `AttachmentImportCoordinator`, `SearchSettingsSection`,
   and `ExternalToolsSettingsSection` must use `ioWorker`, never `runtime.worker()`.
2. Lazy GBNF trigger safety: no catastrophic leading wildcards (`[\\s\\S]*`, `.*`)
   passed to `llama_sampler_init_grammar_lazy_patterns` / `LAZY_TRIGGER_PATTERN`.
3. JNI exception safety: `native.cpp` must guard `llama_sampler_sample` and
   `llama_sampler_accept` against C++ exceptions when lazy grammar is active.
4. Coordinator busy-state integrity: `MainActivity.recomputeBusy()` must account for
   `attachmentCoordinator.isImporting()` and `modelImportCoordinator.isBusy()`, and
   coordinator background tasks must catch `Throwable` (not only `Exception`) so OOM
   cannot permanently deadlock UI input.
5. Parser forward-progress guard: `PdfTextExtractor` must keep delimiter fallback so
   real-world PDF graphics operators cannot trigger an infinite loop.
6. Test-coverage advisory: flags PRs modifying native C++, JNI bridges, parsers, or
   attachment coordinators without updating unit or instrumentation tests.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
APP_JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "notcherry" / "lfm3pocket"
APP_CPP = ROOT / "app" / "src" / "main" / "cpp"
COMMENT_MARKER = "<!-- antigravity-pr-review -->"


@dataclass
class Finding:
    severity: str  # "ERROR" or "WARN"
    rule: str
    path: str
    message: str


def _read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""


def check_repo_invariants(repo_root: Path = ROOT) -> list[Finding]:
    """Run deterministic static checks on critical Hanami runtime invariants."""
    findings: list[Finding] = []
    java_dir = repo_root / "app" / "src" / "main" / "java" / "com" / "notcherry" / "lfm3pocket"
    cpp_dir = repo_root / "app" / "src" / "main" / "cpp"

    main_java = java_dir / "MainActivity.java"
    if main_java.is_file():
        src = _read_text(main_java)
        # 1. Non-inference I/O must not share single-threaded runtime.worker() (hanami-infer).
        if re.search(r"new\s+AttachmentImportCoordinator\.Deps\s*\(\s*this\s*,\s*worker\b", src):
            findings.append(Finding(
                "ERROR",
                "infer-worker-isolation",
                "app/src/main/java/com/notcherry/lfm3pocket/MainActivity.java",
                "AttachmentImportCoordinator is wired to `worker` (hanami-infer). Use `ioWorker` so slow/failing attachment I/O never blocks inference or Cactus STT.",
            ))
        if re.search(r"new\s+ExternalToolsSettingsSection\.Deps\([^;]*\bworker\b", src, re.S):
            findings.append(Finding(
                "ERROR",
                "infer-worker-isolation",
                "app/src/main/java/com/notcherry/lfm3pocket/MainActivity.java",
                "ExternalToolsSettingsSection is wired to `worker` (hanami-infer). Use `ioWorker` so MCP network probes cannot block chat inference.",
            ))
        if re.search(r"new\s+SearchSettingsSection\.Deps\([^;]*\bworker\b", src, re.S):
            findings.append(Finding(
                "ERROR",
                "infer-worker-isolation",
                "app/src/main/java/com/notcherry/lfm3pocket/MainActivity.java",
                "SearchSettingsSection is wired to `worker` (hanami-infer). Use `ioWorker` so SearXNG endpoint tests cannot block chat inference.",
            ))
        # 4. recomputeBusy must include attachment and model import coordinators.
        m_busy = re.search(r"private\s+void\s+recomputeBusy\s*\(\s*\)\s*\{([^}]*)\}", src, re.S)
        if m_busy:
            body = m_busy.group(1)
            if "attachmentCoordinator" not in body or "isImporting()" not in body:
                findings.append(Finding(
                    "ERROR",
                    "busy-state-integrity",
                    "app/src/main/java/com/notcherry/lfm3pocket/MainActivity.java",
                    "`recomputeBusy()` does not include `attachmentCoordinator.isImporting()`, allowing async callbacks to stomp attachment busy state.",
                ))
            if "modelImportCoordinator" not in body or "isBusy()" not in body:
                findings.append(Finding(
                    "ERROR",
                    "busy-state-integrity",
                    "app/src/main/java/com/notcherry/lfm3pocket/MainActivity.java",
                    "`recomputeBusy()` does not include `modelImportCoordinator.isBusy()`, allowing concurrent startup/restore events to clear busy during model load.",
                ))

    # 2. Catastrophic lazy-grammar regex check.
    grammar_java = java_dir / "LfmToolGrammar.java"
    if grammar_java.is_file():
        src = _read_text(grammar_java)
        m_trig = re.search(r'LAZY_TRIGGER_PATTERN\s*=\s*"([^"]+)"', src)
        if m_trig:
            pat = m_trig.group(1)
            if r"[\s\S]*" in pat or r"[\\s\\S]*" in pat or pat.startswith(".*"):
                findings.append(Finding(
                    "ERROR",
                    "lazy-grammar-regex-backtracking",
                    "app/src/main/java/com/notcherry/lfm3pocket/LfmToolGrammar.java",
                    f"`LAZY_TRIGGER_PATTERN` (`{pat}`) contains a leading wildcard that causes O(N^2)-per-token backtracking in llama.cpp `std::regex_search`.",
                ))

    # 3. JNI C++ exception safety around llama_sampler_sample / llama_sampler_accept.
    native_cpp = cpp_dir / "native.cpp"
    if native_cpp.is_file():
        src = _read_text(native_cpp)
        if "make_chat_sampler_with_lazy_grammar" in src:
            # Verify llama_sampler_accept in the sequential decode loop is inside a try block.
            m_loop = re.search(
                r"make_chat_sampler_with_lazy_grammar.*?llama_sampler_free\s*\(\s*sampler\s*\)",
                src,
                re.S,
            )
            if m_loop and ("try {" not in m_loop.group(0) or "catch (" not in m_loop.group(0)):
                findings.append(Finding(
                    "ERROR",
                    "jni-cpp-exception-safety",
                    "app/src/main/cpp/native.cpp",
                    "Lazy grammar decode loop calls `llama_sampler_sample`/`llama_sampler_accept` without a `try { ... } catch (...)` guard; C++ exceptions from llama_grammar crash JNI via `std::terminate()`.",
                ))

    # 4b. AttachmentImportCoordinator must catch Throwable so OOM cannot strand busy=true.
    attach_java = java_dir / "AttachmentImportCoordinator.java"
    if attach_java.is_file():
        src = _read_text(attach_java)
        if "catch (Throwable " not in src:
            findings.append(Finding(
                "ERROR",
                "busy-state-throwable-safety",
                "app/src/main/java/com/notcherry/lfm3pocket/AttachmentImportCoordinator.java",
                "AttachmentImportCoordinator worker tasks must catch `Throwable` (not only `Exception`) so `OutOfMemoryError` cannot permanently lock UI controls.",
            ))

    # 5. PdfTextExtractor delimiter progress guard.
    pdf_java = java_dir / "PdfTextExtractor.java"
    if pdf_java.is_file():
        src = _read_text(pdf_java)
        if "if (i == start)" not in src or "isDelimiter(c)" not in src:
            findings.append(Finding(
                "ERROR",
                "pdf-parser-progress-guard",
                "app/src/main/java/com/notcherry/lfm3pocket/PdfTextExtractor.java",
                "PdfTextExtractor.extractTextOperators() must handle all PDF delimiters outside BT..ET and keep an `if (i == start)` progress guard to prevent infinite loops.",
            ))

    return findings


def check_diff_advisories(changed_files: list[str]) -> list[Finding]:
    """Produce advisory warnings when high-risk areas change without matching tests."""
    findings: list[Finding] = []
    if not changed_files:
        return findings

    high_risk = [
        f for f in changed_files
        if f.startswith("app/src/main/cpp/")
        or f.endswith((
            "NativeBridge.java",
            "PdfTextExtractor.java",
            "AttachmentImportCoordinator.java",
            "LfmToolGrammar.java",
            "ChatSession.java",
            "ModelRuntime.java",
        ))
    ]
    touched_tests = [
        f for f in changed_files
        if f.startswith("app/src/test/") or f.startswith("app/src/androidTest/")
    ]
    if high_risk and not touched_tests:
        findings.append(Finding(
            "WARN",
            "missing-regression-tests",
            ", ".join(high_risk[:4]) + ("…" if len(high_risk) > 4 else ""),
            "High-risk runtime/JNI/parser/attachment code changed without any unit or instrumentation test updates. Add targeted regression coverage before merging.",
        ))

    if any(f.startswith("app/src/main/cpp/") for f in changed_files) and not any(
        f.startswith("app/src/androidTest/") for f in changed_files
    ):
        findings.append(Finding(
            "WARN",
            "native-change-needs-emulator-e2e",
            "app/src/main/cpp/",
            "Native C++ changes detected without an `androidTest` update. Remember that JVM tests mock JNI; ensure real emulator JNI coverage exercises the new C++ code path.",
        ))

    return findings


def git_changed_files(base: str, head: str, repo_root: Path = ROOT) -> list[str]:
    proc = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...{head}"],
        cwd=str(repo_root),
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        return []
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def git_diff_text(base: str, head: str, max_chars: int = 24000, repo_root: Path = ROOT) -> str:
    proc = subprocess.run(
        ["git", "diff", "--unified=3", f"{base}...{head}", "--", "app/src/main/", ".github/"],
        cwd=str(repo_root),
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        return ""
    text = proc.stdout
    if len(text) > max_chars:
        text = text[:max_chars] + "\n... [diff truncated for review budget]"
    return text


def run_gemini_review(diff_text: str, changed_files: list[str]) -> str | None:
    """Optionally request an Antigravity/Gemini AI review if GEMINI_API_KEY is configured."""
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key or not diff_text.strip():
        return None
    model = os.environ.get("ANTIGRAVITY_REVIEW_MODEL", "gemini-2.5-flash").strip() or "gemini-2.5-flash"
    prompt = (
        "You are Antigravity's automated PR reviewer for Hanami (an offline Android LFM3 / llama.cpp assistant).\n"
        "Review ONLY concrete bugs, hangs, deadlocks, JNI/native crashes, busy-state leaks, infinite loops, "
        "privacy/security regressions, or missing E2E coverage in the diff below.\n"
        "Be concise: bullet list of high-signal findings with file+line references, or 'No blocking defects found.'\n\n"
        f"Changed files ({len(changed_files)}):\n"
        + "\n".join(f"- {f}" for f in changed_files[:40])
        + "\n\nDiff:\n```diff\n"
        + diff_text
        + "\n```"
    )
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    payload = json.dumps({
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.1, "maxOutputTokens": 1200},
    }).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="replace"))
        candidates = data.get("candidates") or []
        if not candidates:
            return None
        parts = (candidates[0].get("content") or {}).get("parts") or []
        text = "".join(p.get("text", "") for p in parts if isinstance(p, dict)).strip()
        return text or None
    except (urllib.error.URLError, TimeoutError, ValueError, KeyError) as exc:
        return f"_Gemini AI review unavailable ({type(exc).__name__}); deterministic audit checks above remain authoritative._"


def format_markdown(findings: list[Finding], changed_files: list[str], ai_review: str | None, ai_requested: bool) -> str:
    errors = [f for f in findings if f.severity == "ERROR"]
    warns = [f for f in findings if f.severity == "WARN"]
    status = "❌ Blocking issues found" if errors else ("⚠️ Passed with advisories" if warns else "✅ All deterministic checks passed")
    lines = [
        COMMENT_MARKER,
        f"## Antigravity Automated PR Review — {status}",
        "",
        f"Scanned **{len(changed_files)}** changed file(s) against Hanami runtime, JNI, parser, and busy-state invariants.",
        "",
    ]
    if findings:
        lines.append("### Static Audit Findings")
        for f in findings:
            icon = "❌" if f.severity == "ERROR" else "⚠️"
            lines.append(f"- {icon} **{f.severity}** (`{f.rule}`) `{f.path}`: {f.message}")
        lines.append("")
    else:
        lines.append("### Static Audit Findings")
        lines.append("- ✅ No thread-isolation, lazy-grammar regex, JNI exception, busy-state, or parser-loop regressions detected.")
        lines.append("")

    if ai_review:
        lines.append("### Antigravity AI Diff Review")
        lines.append(ai_review)
        lines.append("")
    elif ai_requested:
        lines.append("### Antigravity AI Diff Review")
        lines.append(
            "_Skipped: set repository secret `GEMINI_API_KEY` to enable automated LLM diff commentary alongside the deterministic checks above._"
        )
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def run_self_test() -> None:
    # 1. Current repo must pass all ERROR-level invariant checks.
    live = [f for f in check_repo_invariants(ROOT) if f.severity == "ERROR"]
    assert not live, f"Live repo failed invariant checks: {live}"

    # 2. Poisoned temp tree must trigger every deterministic ERROR rule.
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        java = tmp / "app" / "src" / "main" / "java" / "com" / "notcherry" / "lfm3pocket"
        cpp = tmp / "app" / "src" / "main" / "cpp"
        java.mkdir(parents=True)
        cpp.mkdir(parents=True)

        (java / "MainActivity.java").write_text(
            "class MainActivity {\n"
            "  void init() {\n"
            "    new AttachmentImportCoordinator.Deps(this, worker, null);\n"
            "    new SearchSettingsSection.Deps(a, worker, b);\n"
            "    new ExternalToolsSettingsSection.Deps(a, worker, b);\n"
            "  }\n"
            "  private void recomputeBusy() { busy = generating; }\n"
            "}\n",
            encoding="utf-8",
        )
        (java / "LfmToolGrammar.java").write_text(
            'class LfmToolGrammar { public static final String LAZY_TRIGGER_PATTERN = "[\\\\s\\\\S]*?(<tool_call>)"; }\n',
            encoding="utf-8",
        )
        (java / "AttachmentImportCoordinator.java").write_text(
            "class AttachmentImportCoordinator { void f() { try {} catch (Exception e) {} } }\n",
            encoding="utf-8",
        )
        (java / "PdfTextExtractor.java").write_text(
            "class PdfTextExtractor { void extractTextOperators() {} }\n",
            encoding="utf-8",
        )
        (cpp / "native.cpp").write_text(
            "void run() { auto s = make_chat_sampler_with_lazy_grammar(); llama_sampler_accept(s, 0); llama_sampler_free(sampler); }\n",
            encoding="utf-8",
        )

        poisoned = check_repo_invariants(tmp)
        rules = {f.rule for f in poisoned if f.severity == "ERROR"}
        expected = {
            "infer-worker-isolation",
            "busy-state-integrity",
            "lazy-grammar-regex-backtracking",
            "jni-cpp-exception-safety",
            "busy-state-throwable-safety",
            "pdf-parser-progress-guard",
        }
        missing = expected - rules
        assert not missing, f"Self-test missed expected rules: {missing} (got {rules})"

    # 3. Diff advisory check catches untested C++ / parser changes.
    advisories = check_diff_advisories(["app/src/main/cpp/native.cpp"])
    adv_rules = {f.rule for f in advisories}
    assert "missing-regression-tests" in adv_rules and "native-change-needs-emulator-e2e" in adv_rules, adv_rules

    print("antigravity-pr-review self-test: OK")


def main() -> int:
    parser = argparse.ArgumentParser(description="Antigravity automated PR review for Hanami.")
    parser.add_argument("--self-test", action="store_true", help="Run built-in negative-control self-tests.")
    parser.add_argument("--base", default="", help="Base git ref for PR diff.")
    parser.add_argument("--head", default="HEAD", help="Head git ref for PR diff.")
    parser.add_argument("--markdown-out", default="", help="Optional path to write markdown review report.")
    parser.add_argument("--gemini", action="store_true", help="Run optional Gemini API diff review if GEMINI_API_KEY is set.")
    parser.add_argument("--fail-on-error", action="store_true", help="Exit non-zero if any ERROR finding is reported.")
    args = parser.parse_args()

    if args.self_test:
        run_self_test()
        return 0

    changed = git_changed_files(args.base, args.head) if args.base else []
    findings = check_repo_invariants(ROOT) + check_diff_advisories(changed)
    ai_review = None
    if args.gemini and args.base:
        diff_text = git_diff_text(args.base, args.head)
        ai_review = run_gemini_review(diff_text, changed)

    report = format_markdown(findings, changed, ai_review, args.gemini)
    if args.markdown_out:
        Path(args.markdown_out).write_text(report, encoding="utf-8")
    sys.stdout.write(report)

    has_error = any(f.severity == "ERROR" for f in findings)
    return 1 if (args.fail_on_error and has_error) else 0


if __name__ == "__main__":
    raise SystemExit(main())
