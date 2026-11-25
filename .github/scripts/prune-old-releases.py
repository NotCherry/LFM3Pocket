#!/usr/bin/env python3
"""Prune old GitHub Releases so only the newest N published releases remain.

Deletes only the GitHub Release objects (and their attached assets) via the REST
API. Git tags are intentionally preserved so semantic version discovery
(`next-hanami-version.py`) stays monotonic and git history remains intact.
Draft releases are never deleted.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional, Sequence, Tuple

SEMVER_TAG_RE = re.compile(r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
NEXT_LINK_RE = re.compile(r'<([^>]+)>;\s*rel="next"')


def _parse_semver(tag: str) -> Optional[Tuple[int, int, int]]:
    match = SEMVER_TAG_RE.match((tag or "").strip())
    if not match:
        return None
    return int(match.group(1)), int(match.group(2)), int(match.group(3))


def _release_sort_key(release: Dict[str, Any]) -> Tuple[int, int, int, int, str, str, int]:
    """Sort key placing highest semver tags first, then non-semver by recency."""
    tag = str(release.get("tag_name") or "")
    parsed = _parse_semver(tag)
    published = str(release.get("published_at") or "")
    created = str(release.get("created_at") or "")
    rel_id = int(release.get("id") or 0)
    if parsed is not None:
        return (1, parsed[0], parsed[1], parsed[2], published, created, rel_id)
    return (0, 0, 0, 0, published, created, rel_id)


def _asset_bytes(release: Dict[str, Any]) -> int:
    return sum(int(a.get("size") or 0) for a in (release.get("assets") or []))


def plan_prune(
    releases: Sequence[Dict[str, Any]],
    keep: int = 5,
    protect_tag: Optional[str] = None,
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]]]:
    """Partition releases into (kept, to_delete, skipped_drafts).

    - Draft releases are never deleted and are returned in skipped_drafts.
    - Published releases are ordered newest-first (semver tags descending, then
      non-semver releases by timestamp descending).
    - If `protect_tag` matches a published release, it is guaranteed to be kept
      within the `keep` budget.
    """
    if keep < 1:
        raise ValueError(f"--keep must be >= 1 (got {keep})")

    skipped_drafts: List[Dict[str, Any]] = []
    published: List[Dict[str, Any]] = []
    for rel in releases:
        if rel.get("draft"):
            skipped_drafts.append(rel)
        else:
            published.append(rel)

    ordered = sorted(published, key=_release_sort_key, reverse=True)

    kept: List[Dict[str, Any]] = []
    kept_ids = set()

    if protect_tag:
        for rel in ordered:
            if str(rel.get("tag_name") or "") == protect_tag and rel.get("id") not in kept_ids:
                kept.append(rel)
                kept_ids.add(rel.get("id"))

    for rel in ordered:
        if len(kept) >= keep:
            break
        if rel.get("id") not in kept_ids:
            kept.append(rel)
            kept_ids.add(rel.get("id"))

    to_delete = [rel for rel in ordered if rel.get("id") not in kept_ids]
    return kept, to_delete, skipped_drafts


def _api_request(url: str, token: str, method: str = "GET") -> Tuple[int, Any, Dict[str, str]]:
    req = urllib.request.Request(
        url,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "prune-old-releases",
        },
    )
    with urllib.request.urlopen(req) as resp:
        status = resp.status
        headers = {k.lower(): v for k, v in resp.headers.items()}
        body = resp.read()
        data = json.loads(body.decode("utf-8")) if body else None
        return status, data, headers


def fetch_all_releases(repo: str, api_url: str, token: str) -> List[Dict[str, Any]]:
    base = api_url.rstrip("/")
    url: Optional[str] = f"{base}/repos/{repo.strip('/')}/releases?per_page=100"
    results: List[Dict[str, Any]] = []
    while url:
        _, data, headers = _api_request(url, token, method="GET")
        if not isinstance(data, list):
            raise RuntimeError(f"Expected list from releases API, got {type(data).__name__}")
        results.extend(data)
        link = headers.get("link", "")
        next_match = NEXT_LINK_RE.search(link)
        url = next_match.group(1) if next_match else None
    return results


def delete_release(repo: str, api_url: str, token: str, release_id: int) -> None:
    base = api_url.rstrip("/")
    url = f"{base}/repos/{repo.strip('/')}/releases/{release_id}"
    _api_request(url, token, method="DELETE")


def self_test() -> None:
    def mk(
        rid: int,
        tag: str,
        published: str = "2026-11-24T00:00:00Z",
        draft: bool = False,
        prerelease: bool = False,
        asset_mb: int = 20,
    ) -> Dict[str, Any]:
        return {
            "id": rid,
            "tag_name": tag,
            "draft": draft,
            "prerelease": prerelease,
            "published_at": published,
            "created_at": published,
            "assets": [{"name": f"{tag}.apk", "size": asset_mb * 1024 * 1024}],
        }

    # 1. Keeps newest 5 semver releases even if returned out of order, deletes older + stale latest.
    sample = [
        mk(1, "v0.1.0", "2026-11-14T00:00:00Z"),
        mk(2, "latest", "2026-11-16T00:00:00Z", prerelease=True),
        mk(10, "v0.16.10", "2026-11-24T17:00:00Z"),
        mk(8, "v0.16.8", "2026-11-24T00:00:00Z"),
        mk(9, "v0.16.9", "2026-11-24T15:00:00Z"),
        mk(6, "v0.16.6", "2026-11-23T23:00:00Z"),
        mk(7, "v0.16.7", "2026-11-24T00:15:00Z"),
        mk(5, "v0.16.5", "2026-11-23T22:00:00Z"),
        mk(99, "v0.17.0-draft", "2026-11-25T00:00:00Z", draft=True),
    ]
    kept, to_delete, drafts = plan_prune(sample, keep=5, protect_tag="v0.16.10")
    assert [r["tag_name"] for r in kept] == ["v0.16.10", "v0.16.9", "v0.16.8", "v0.16.7", "v0.16.6"]
    assert [r["tag_name"] for r in to_delete] == ["v0.16.5", "v0.1.0", "latest"]
    assert [r["tag_name"] for r in drafts] == ["v0.17.0-draft"]

    # 2. Protects `latest` when manually dispatched while capping total kept at 5.
    kept2, to_delete2, _ = plan_prune(sample, keep=5, protect_tag="latest")
    assert [r["tag_name"] for r in kept2] == ["latest", "v0.16.10", "v0.16.9", "v0.16.8", "v0.16.7"]
    assert [r["tag_name"] for r in to_delete2] == ["v0.16.6", "v0.16.5", "v0.1.0"]

    # 3. No-op when <= keep published releases exist.
    small = [mk(1, "v0.1.0"), mk(2, "v0.2.0"), mk(3, "v0.3.0")]
    kept3, to_delete3, _ = plan_prune(small, keep=5)
    assert [r["tag_name"] for r in kept3] == ["v0.3.0", "v0.2.0", "v0.1.0"]
    assert to_delete3 == []

    # 4. Rejects keep < 1.
    try:
        plan_prune(small, keep=0)
        raise AssertionError("Expected ValueError for keep=0")
    except ValueError:
        pass

    print("prune-old-releases.py: self-test ok")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="Run unit self-tests and exit.")
    parser.add_argument("--repo", help="GitHub repository in owner/repo format.")
    parser.add_argument("--keep", type=int, default=5, help="Number of newest releases to keep (default: 5).")
    parser.add_argument("--protect-tag", default="", help="Optional tag that must not be deleted.")
    parser.add_argument("--api-url", default=os.environ.get("GITHUB_API_URL", "https://api.github.com"))
    parser.add_argument("--token-env", default="GH_TOKEN", help="Env var holding GitHub API token.")
    parser.add_argument("--dry-run", action="store_true", help="Print prune plan without deleting anything.")
    parser.add_argument("--github-output", default="", help="Optional path to write GitHub Actions outputs.")
    args = parser.parse_args(list(argv) if argv is not None else None)

    if args.self_test:
        self_test()
        return 0

    if not args.repo:
        print("Missing required --repo owner/repo", file=sys.stderr)
        return 2

    token = os.environ.get(args.token_env, "")
    if not token:
        print(f"Missing token in environment variable {args.token_env}", file=sys.stderr)
        return 2

    try:
        releases = fetch_all_releases(args.repo, args.api_url, token)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        print(f"GitHub API error listing releases ({e.code}): {body}", file=sys.stderr)
        return 1

    kept, to_delete, skipped_drafts = plan_prune(
        releases,
        keep=args.keep,
        protect_tag=args.protect_tag or None,
    )

    kept_tags = [str(r.get("tag_name") or f"id:{r.get('id')}") for r in kept]
    print(f"Keeping {len(kept)} release(s): {', '.join(kept_tags) if kept_tags else '(none)'}")
    if skipped_drafts:
        print(f"Skipping {len(skipped_drafts)} draft release(s).")

    deleted_tags: List[str] = []
    freed_bytes = 0
    for rel in to_delete:
        rel_id = int(rel["id"])
        tag = str(rel.get("tag_name") or f"id:{rel_id}")
        size_bytes = _asset_bytes(rel)
        size_mb = size_bytes / (1024 * 1024)
        if args.dry_run:
            print(f"[dry-run] Would delete release {tag} (id={rel_id}, assets={size_mb:.1f} MB; git tag kept)")
            deleted_tags.append(tag)
            freed_bytes += size_bytes
        else:
            try:
                delete_release(args.repo, args.api_url, token, rel_id)
            except urllib.error.HTTPError as e:
                body = e.read().decode("utf-8", "replace")
                print(f"Failed to delete release {tag} (id={rel_id}, HTTP {e.code}): {body}", file=sys.stderr)
                return 1
            print(f"Deleted release {tag} (id={rel_id}, assets={size_mb:.1f} MB; git tag kept)")
            deleted_tags.append(tag)
            freed_bytes += size_bytes

    freed_mb = freed_bytes / (1024 * 1024)
    action = "Would delete" if args.dry_run else "Deleted"
    print(f"{action} {len(deleted_tags)} old release(s), freeing {freed_mb:.1f} MB of release assets.")

    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8") as out:
            out.write(f"kept_count={len(kept)}\n")
            out.write(f"deleted_count={len(deleted_tags)}\n")
            out.write(f"freed_mb={freed_mb:.1f}\n")
            out.write(f"kept_tags={', '.join(kept_tags)}\n")
            out.write(f"deleted_tags={', '.join(deleted_tags)}\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
