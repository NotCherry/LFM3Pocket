#!/usr/bin/env python3
"""Choose runs-on for CHERRY-TREE or GitHub-hosted ubuntu-latest.

The pick-runner job calls this on ubuntu-latest. Fork pull requests always
stay on ubuntu-latest. Otherwise an online, idle runner labeled cherry-tree
is selected when RUNNER_STATUS_TOKEN can list it. A busy runner, a missing
secret, or a failed API call falls back to ubuntu-latest and still exits 0.

Stdout is one JSON value and nothing else:
  ["self-hosted","cherry-tree"]
  "ubuntu-latest"
"""

from __future__ import annotations

import io
import json
import os
import re
import sys
import urllib.error
import urllib.request
from typing import Any, Callable, Mapping, Optional, Sequence

GITHUB_HOSTED = '"ubuntu-latest"'
SELF_HOSTED = '["self-hosted","cherry-tree"]'
REPOSITORY_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
API_VERSION = "2022-11-28"
CHERRY_TREE = "cherry-tree"


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def label_names(runner: Mapping[str, Any]) -> set[str]:
    labels = runner.get("labels") or []
    if not isinstance(labels, list):
        return set()
    names: set[str] = set()
    for label in labels:
        if isinstance(label, str):
            names.add(label)
        elif isinstance(label, dict) and isinstance(label.get("name"), str):
            names.add(label["name"])
    return names


def is_untrusted_head(event_name: str, head_repo: str, repository: str) -> bool:
    """True when this event must not run on the owner's machine.

    pull_request from a fork (including a missing head repo) always qualifies.
    Any other event that names a different head repository qualifies too, so a
    later pull_request_target workflow cannot reuse this selector unchanged.
    """
    head = (head_repo or "").strip().lower()
    base = (repository or "").strip().lower()
    if event_name == "pull_request":
        return head != base
    return bool(head) and head != base


def select_runs_on(
    event_name: str,
    head_repo: str,
    repository: str,
    token_present: bool,
    runners: Optional[Sequence[Mapping[str, Any]]],
) -> str:
    if is_untrusted_head(event_name, head_repo, repository):
        shown = (head_repo or "").strip() or "unknown"
        print(f"Untrusted head repository {shown}; using ubuntu-latest.", file=sys.stderr)
        return GITHUB_HOSTED
    if not token_present:
        print("RUNNER_STATUS_TOKEN is missing; using ubuntu-latest.", file=sys.stderr)
        return GITHUB_HOSTED
    if runners is None:
        raise RuntimeError("runner list missing")

    idle = []
    busy = []
    for runner in runners:
        if not isinstance(runner, Mapping):
            continue
        if CHERRY_TREE not in label_names(runner):
            continue
        status = runner.get("status")
        is_busy = bool(runner.get("busy"))
        print(
            f"runner {runner.get('name')!r} status={status} busy={is_busy}",
            file=sys.stderr,
        )
        if status != "online":
            continue
        if is_busy:
            busy.append(runner)
        else:
            idle.append(runner)

    if not idle:
        if busy:
            print(
                "cherry-tree is online but busy; using ubuntu-latest.",
                file=sys.stderr,
            )
        else:
            print("No online cherry-tree runner; using ubuntu-latest.", file=sys.stderr)
        return GITHUB_HOSTED
    chosen = idle[0]
    print(f"Using cherry-tree ({chosen.get('name')}, idle).", file=sys.stderr)
    return SELF_HOSTED


def _request_page(repository: str, token: str, page: int) -> Mapping[str, Any]:
    url = (
        "https://api.github.com/repos/"
        f"{repository}/actions/runners?per_page=100&page={page}"
    )
    request = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": API_VERSION,
            "User-Agent": "lfm3pocket-pick-runner",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            status = getattr(response, "status", 200)
            raw = response.read()
    except urllib.error.HTTPError as error:
        try:
            error.read()
        except Exception:
            pass
        raise RuntimeError(f"GitHub API HTTP {error.code}") from None
    except Exception:
        raise RuntimeError("GitHub API request failed") from None
    if status != 200:
        raise RuntimeError(f"GitHub API HTTP {status}")
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise RuntimeError("GitHub API returned invalid JSON") from None
    if not isinstance(payload, dict):
        raise RuntimeError("GitHub API returned an unexpected payload")
    return payload


def fetch_runners(repository: str, token: str) -> list:
    if not REPOSITORY_RE.fullmatch(repository or ""):
        raise ValueError("Refusing unexpected repository id")
    collected: list = []
    seen_ids: set[int] = set()
    for page in range(1, 11):
        payload = _request_page(repository, token, page)
        batch = payload.get("runners")
        if not isinstance(batch, list):
            raise RuntimeError("GitHub API response has no runners list")
        fresh: list = []
        repeated = False
        for runner in batch:
            if isinstance(runner, dict) and isinstance(runner.get("id"), int):
                identity = runner["id"]
                if identity in seen_ids:
                    repeated = True
                    continue
                seen_ids.add(identity)
            fresh.append(runner)
        if batch and not fresh:
            break
        collected.extend(fresh)
        total = payload.get("total_count")
        if repeated or len(batch) < 100 or (isinstance(total, int) and len(collected) >= total):
            break
    return collected


def decide_from_env(
    env: Mapping[str, str],
    fetch: Callable[[str, str], Sequence[Mapping[str, Any]]] = fetch_runners,
) -> str:
    event_name = env.get("EVENT_NAME") or ""
    head_repo = env.get("HEAD_REPO") or ""
    repository = env.get("REPOSITORY") or ""
    token = (env.get("RUNNER_STATUS_TOKEN") or "").strip()
    if is_untrusted_head(event_name, head_repo, repository) or not token:
        return select_runs_on(event_name, head_repo, repository, bool(token), None)
    try:
        runners = fetch(repository, token)
    except Exception as error:
        message = str(error).replace(token, "[redacted]")
        print(
            f"Runner list failed ({error.__class__.__name__}: {message}); using ubuntu-latest.",
            file=sys.stderr,
        )
        return GITHUB_HOSTED
    try:
        return select_runs_on(event_name, head_repo, repository, True, runners)
    except Exception:
        print("Runner selection failed; using ubuntu-latest.", file=sys.stderr)
        return GITHUB_HOSTED


def _runner(name: str, status: str, busy: bool, labels: Sequence[str], runner_id: int = 1) -> dict:
    return {
        "id": runner_id,
        "name": name,
        "status": status,
        "busy": busy,
        "labels": [{"name": label, "type": "custom"} for label in labels],
    }


def _capture(env: Mapping[str, str], fetch: Callable[[str, str], Sequence[Mapping[str, Any]]]) -> tuple[str, str]:
    stderr = io.StringIO()
    previous = sys.stderr
    sys.stderr = stderr
    try:
        result = decide_from_env(env, fetch=fetch)
    finally:
        sys.stderr = previous
    return result, stderr.getvalue()


def self_test() -> None:
    expect(json.loads(SELF_HOSTED) == ["self-hosted", "cherry-tree"], "self-hosted JSON")
    expect(json.loads(GITHUB_HOSTED) == "ubuntu-latest", "hosted JSON")

    online = [_runner("CHERRY-TREE", "online", False, ["self-hosted", "cherry-tree"])]
    repo = "NotCherry/LFM3Pocket"
    base_env = {
        "EVENT_NAME": "push",
        "HEAD_REPO": "",
        "REPOSITORY": repo,
        "RUNNER_STATUS_TOKEN": "github_pat_SUPERSECRET",
    }

    def fail_fetch(repository: str, token: str) -> list:
        raise RuntimeError(f"should not fetch token={token} repo={repository}")

    result, log = _capture(
        {
            "EVENT_NAME": "pull_request",
            "HEAD_REPO": "attacker/LFM3Pocket",
            "REPOSITORY": repo,
            "RUNNER_STATUS_TOKEN": "github_pat_SUPERSECRET",
        },
        fail_fetch,
    )
    expect(result == GITHUB_HOSTED, "fork PR uses GitHub-hosted")
    expect("SUPERSECRET" not in log, "fork path leaked the token")

    result, _log = _capture(
        {
            "EVENT_NAME": "pull_request",
            "HEAD_REPO": "",
            "REPOSITORY": repo,
            "RUNNER_STATUS_TOKEN": "github_pat_SUPERSECRET",
        },
        fail_fetch,
    )
    expect(result == GITHUB_HOSTED, "pull_request with no head repo stays hosted")

    result, _log = _capture(
        {
            "EVENT_NAME": "pull_request",
            "HEAD_REPO": "notcherry/lfm3pocket",
            "REPOSITORY": repo,
            "RUNNER_STATUS_TOKEN": "github_pat_SUPERSECRET",
        },
        lambda repository, token: online,
    )
    expect(result == SELF_HOSTED, "same-repo PR is case-insensitive")

    result, _log = _capture(
        {
            "EVENT_NAME": "pull_request_target",
            "HEAD_REPO": "attacker/LFM3Pocket",
            "REPOSITORY": repo,
            "RUNNER_STATUS_TOKEN": "github_pat_SUPERSECRET",
        },
        fail_fetch,
    )
    expect(result == GITHUB_HOSTED, "fork pull_request_target stays hosted")

    result, log = _capture({**base_env, "RUNNER_STATUS_TOKEN": ""}, fail_fetch)
    expect(result == GITHUB_HOSTED, "missing token falls back")
    expect("SUPERSECRET" not in log, "missing-token path leaked a secret")

    result, _log = _capture({**base_env, "RUNNER_STATUS_TOKEN": "   "}, fail_fetch)
    expect(result == GITHUB_HOSTED, "blank token falls back")

    def boom(repository: str, token: str) -> list:
        raise RuntimeError(f"HTTP 403 token={token}")

    result, log = _capture(base_env, boom)
    expect(result == GITHUB_HOSTED, "API failure falls back")
    expect("SUPERSECRET" not in log, "API failure leaked the token")
    expect("HTTP 403" in log, "API failure keeps the status class message")
    expect("[redacted]" in log, "API failure redacts the token")

    result, _log = _capture(base_env, lambda repository, token: online)
    expect(result == SELF_HOSTED, "online idle runner is selected")

    result, _log = _capture(
        base_env,
        lambda repository, token: [_runner("CHERRY-TREE", "online", True, ["self-hosted", "cherry-tree"])],
    )
    expect(result == GITHUB_HOSTED, "online busy runner falls back")

    result, _log = _capture(
        base_env,
        lambda repository, token: [
            _runner("busy-box", "online", True, ["cherry-tree"], 2),
            _runner("CHERRY-TREE", "online", False, ["cherry-tree"], 3),
        ],
    )
    expect(result == SELF_HOSTED, "idle runner is preferred among online runners")

    result, _log = _capture(
        base_env,
        lambda repository, token: [_runner("CHERRY-TREE", "offline", False, ["cherry-tree"])],
    )
    expect(result == GITHUB_HOSTED, "offline runner falls back")

    result, _log = _capture(
        base_env,
        lambda repository, token: [_runner("other", "online", False, ["self-hosted", "CHERRY-TREE"])],
    )
    expect(result == GITHUB_HOSTED, "label match is exact")

    result, _log = _capture(
        {"EVENT_NAME": "workflow_dispatch", "HEAD_REPO": "", "REPOSITORY": repo, "RUNNER_STATUS_TOKEN": "t"},
        lambda repository, token: online,
    )
    expect(result == SELF_HOSTED, "workflow_dispatch may use the self-hosted runner")

    calls = []

    class Response:
        status = 200

        def __init__(self, body: bytes) -> None:
            self._body = body

        def read(self) -> bytes:
            return self._body

        def __enter__(self) -> "Response":
            return self

        def __exit__(self, *args: object) -> bool:
            return False

    pages = {
        1: {
            "total_count": 101,
            "runners": [_runner(f"other-{i}", "online", False, ["self-hosted"], i) for i in range(1, 101)],
        },
        2: {
            "total_count": 101,
            "runners": [_runner("CHERRY-TREE", "online", False, ["self-hosted", "cherry-tree"], 101)],
        },
    }

    original = urllib.request.urlopen

    def fake_urlopen(request: urllib.request.Request, timeout: int = 0) -> Response:
        from urllib.parse import parse_qs, urlparse

        expect(timeout == 20, "request timeout")
        parsed = urlparse(request.full_url)
        expect(parsed.scheme == "https", "API scheme")
        expect(parsed.netloc == "api.github.com", "API host")
        expect(parsed.path == "/repos/NotCherry/LFM3Pocket/actions/runners", f"unexpected path {parsed.path}")
        query = parse_qs(parsed.query)
        expect(query.get("per_page") == ["100"], "page size")
        page = int(query["page"][0])
        header = request.get_header("Authorization") or ""
        expect(header == "Bearer github_pat_test", "authorization header")
        expect("github_pat_test" not in request.full_url, "token leaked into the URL")
        calls.append(page)
        return Response(json.dumps(pages[page]).encode())

    urllib.request.urlopen = fake_urlopen
    try:
        listed = fetch_runners(repo, "github_pat_test")
        expect(calls == [1, 2], f"pagination calls {calls}")
        expect(any(CHERRY_TREE in label_names(item) for item in listed), "second page kept")
        try:
            fetch_runners("https://evil.example/repos", "github_pat_test")
        except ValueError:
            pass
        else:
            raise AssertionError("unexpected repository id was accepted")
        try:
            fetch_runners("NotCherry/LFM3Pocket?page=1", "github_pat_test")
        except ValueError:
            pass
        else:
            raise AssertionError("query injection was accepted")

        def forbidden(request: urllib.request.Request, timeout: int = 0) -> Response:
            raise urllib.error.HTTPError(
                "https://api.github.com/repos/NotCherry/LFM3Pocket/actions/runners",
                404,
                "Not Found",
                hdrs=None,
                fp=io.BytesIO(b'{"message":"missing"}'),
            )

        urllib.request.urlopen = forbidden
        result, log = _capture(base_env, fetch_runners)
        expect(result == GITHUB_HOSTED, "HTTP error falls back")
        expect("missing" not in log, "error body was logged")
        expect("github_pat_SUPERSECRET" not in log, "HTTP error leaked the token")
    finally:
        urllib.request.urlopen = original

    print("Runner selection: fork, token, API, and label controls passed.")


def main(argv: Sequence[str]) -> int:
    if argv[1:] == ["--self-test"]:
        self_test()
        return 0
    if argv[1:]:
        print("usage: pick-runner.py [--self-test]", file=sys.stderr)
        return 2
    sys.stdout.write(decide_from_env(os.environ))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
