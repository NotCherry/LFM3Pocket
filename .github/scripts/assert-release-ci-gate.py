#!/usr/bin/env python3
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def _matching_runs(payload, sha):
    runs = payload.get("workflow_runs") or []
    return [r for r in runs if r.get("head_sha") == sha]


def _select_latest(runs):
    if not runs:
        return None
    # GitHub returns created_at ISO8601; string sort is stable for this format.
    # Fall back to run_number/id for determinism.
    return sorted(
        runs,
        key=lambda r: (
            r.get("created_at") or "",
            int(r.get("run_number") or 0),
            int(r.get("id") or 0),
        ),
    )[-1]


def evaluate_runs(payload, sha):
    """Return (ok, reason, run).

    Accept only when a matching completed run for the exact SHA succeeded.
    Reject failed/cancelled/skipped/timed_out/action_required/non-success conclusions,
    and reject when no matching completed run exists (including stale/wrong-SHA-only runs).
    """
    matches = _matching_runs(payload, sha)
    if not matches:
        return False, f"no Android Stage 1 CI run found for exact SHA {sha}", None

    run = _select_latest(matches)
    status = run.get("status") or ""
    conclusion = run.get("conclusion") or ""
    run_url = run.get("html_url") or ""
    run_sha = run.get("head_sha") or ""
    if run_sha != sha:
        return False, f"selected run head_sha mismatch: {run_sha} != {sha}", run
    if status != "completed":
        return False, f"matching run for {sha} not completed: status={status} {run_url}".strip(), run
    if conclusion != "success":
        return False, (
            f"Android Stage 1 CI did not succeed for {sha}: conclusion={conclusion} {run_url}"
        ).strip(), run
    return True, f"Android Stage 1 CI succeeded for {sha}: {run_url}".strip(), run


def _load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _api_get_json(url, token):
    req = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "assert-release-ci-gate",
        },
    )
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)


def cmd_check_response(args):
    payload = _load_json(args.response)
    ok, reason, _ = evaluate_runs(payload, args.sha)
    print(reason)
    return 0 if ok else 1


def cmd_wait(args):
    token = os.environ.get(args.token_env, "")
    if not token:
        print(f"Missing token in environment variable {args.token_env}", file=sys.stderr)
        return 2
    workflow = urllib.parse.quote(args.workflow)
    repo = args.repo.strip("/")
    base = args.api_url.rstrip("/")
    query = urllib.parse.urlencode({"head_sha": args.sha, "per_page": 20})
    url = f"{base}/repos/{repo}/actions/workflows/{workflow}/runs?{query}"

    for attempt in range(1, args.attempts + 1):
        try:
            payload = _api_get_json(url, token)
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")
            print(f"GitHub API error ({e.code}): {body}", file=sys.stderr)
            return 2
        except Exception as e:
            print(f"GitHub API request failed: {e}", file=sys.stderr)
            return 2

        ok, reason, run = evaluate_runs(payload, args.sha)
        if ok:
            print(reason)
            return 0
        if run is not None and (run.get("status") or "") == "completed":
            print(reason, file=sys.stderr)
            return 1
        if attempt < args.attempts:
            print(f"Waiting for Android Stage 1 CI for {args.sha} (attempt {attempt}/{args.attempts}): {reason}")
            time.sleep(args.sleep)
        else:
            print(f"Timed out waiting for successful Android Stage 1 CI for {args.sha}: {reason}", file=sys.stderr)
            return 1

    print(f"Timed out waiting for successful Android Stage 1 CI for {args.sha}", file=sys.stderr)
    return 1


def _run_case(name, payload, sha, expect_ok, expect_substrings=()):
    ok, reason, _ = evaluate_runs(payload, sha)
    if ok != expect_ok:
        raise AssertionError(f"{name}: expected ok={expect_ok} got {ok} reason={reason!r}")
    for s in expect_substrings:
        if s not in reason:
            raise AssertionError(f"{name}: expected {s!r} in reason {reason!r}")


def cmd_self_test(args=None):
    target = "a" * 40
    other = "b" * 40

    def run(sha, conclusion, status="completed", created="2026-11-19T10:00:00Z", n=1):
        return {
            "id": n,
            "run_number": n,
            "head_sha": sha,
            "status": status,
            "conclusion": conclusion,
            "created_at": created,
            "html_url": f"https://example.invalid/runs/{n}",
        }

    # Accept exact-SHA success.
    _run_case(
        "accept-success",
        {"workflow_runs": [run(target, "success")]},
        target,
        True,
        ("succeeded", target),
    )

    # Reject each non-success completed conclusion.
    for conclusion in ["failure", "cancelled", "skipped", "timed_out", "action_required", "neutral", "stale"]:
        _run_case(
            f"reject-{conclusion}",
            {"workflow_runs": [run(target, conclusion)]},
            target,
            False,
            ("did not succeed", conclusion),
        )

    # Reject when no matching completed run exists for target SHA.
    _run_case("reject-no-runs", {"workflow_runs": []}, target, False, ("no Android Stage 1 CI run found", target))
    _run_case(
        "reject-no-completed-match",
        {"workflow_runs": [run(target, None, status="in_progress")]},
        target,
        False,
        ("not completed",),
    )

    # Reject stale/wrong-SHA runs: success on different SHA must not count.
    _run_case(
        "reject-wrong-sha-success",
        {"workflow_runs": [run(other, "success")]},
        target,
        False,
        ("no Android Stage 1 CI run found", target),
    )
    _run_case(
        "reject-wrong-sha-success-target-failed",
        {"workflow_runs": [run(other, "success", created="2026-11-19T11:00:00Z", n=2), run(target, "failure", created="2026-11-19T10:00:00Z", n=1)]},
        target,
        False,
        ("did not succeed", "failure"),
    )
    _run_case(
        "reject-wrong-sha-only-when-target-missing",
        {"workflow_runs": [run(other, "success", n=2), run(target, None, status="queued", n=1)]},
        target,
        False,
        ("not completed",),
    )

    # If multiple matching runs exist, choose newest matching run.
    _run_case(
        "accept-newest-matching-success",
        {
            "workflow_runs": [
                run(target, "failure", created="2026-11-19T10:00:00Z", n=1),
                run(target, "success", created="2026-11-19T10:05:00Z", n=2),
                run(other, "success", created="2026-11-19T10:06:00Z", n=3),
            ]
        },
        target,
        True,
        ("succeeded",),
    )
    _run_case(
        "reject-newest-matching-failure",
        {
            "workflow_runs": [
                run(target, "success", created="2026-11-19T10:00:00Z", n=1),
                run(target, "failure", created="2026-11-19T10:05:00Z", n=2),
            ]
        },
        target,
        False,
        ("did not succeed", "failure"),
    )

    print("assert-release-ci-gate: self-test passed")
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description="Assert Android Stage 1 CI gate for release publishing.")
    sub = p.add_subparsers(dest="cmd")

    st = sub.add_parser("self-test", help="Run negative/positive control tests.")
    st.set_defaults(func=cmd_self_test)

    cr = sub.add_parser("check-response", help="Evaluate a workflow-runs JSON payload.")
    cr.add_argument("--response", required=True, help="Path to workflow-runs JSON payload.")
    cr.add_argument("--sha", required=True, help="Exact commit SHA that must have succeeded.")
    cr.set_defaults(func=cmd_check_response)

    w = sub.add_parser("wait", help="Poll GitHub Actions until exact-SHA CI succeeds or fails.")
    w.add_argument("--repo", required=True, help="owner/repo")
    w.add_argument("--workflow", required=True, help="Workflow file name or id, e.g. build-apk.yml")
    w.add_argument("--sha", required=True, help="Exact commit SHA that must succeed")
    w.add_argument("--api-url", default=os.environ.get("GITHUB_API_URL", "https://api.github.com"))
    w.add_argument("--token-env", default="GH_TOKEN")
    w.add_argument("--attempts", type=int, default=180)
    w.add_argument("--sleep", type=int, default=30)
    w.set_defaults(func=cmd_wait)

    # Convenience aliases.
    p.add_argument("--self-test", action="store_true", help=argparse.SUPPRESS)
    args = p.parse_args(argv)
    if args.self_test or args.cmd == "self-test":
        return cmd_self_test(args)
    if args.cmd is None:
        p.print_help()
        return 2
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
