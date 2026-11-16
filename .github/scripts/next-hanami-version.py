#!/usr/bin/env python3
"""Compute the next Hanami vMAJOR.MINOR.PATCH tag from git history.

Default bump is patch. Conventional commits can raise it:
  feat: / feat(scope):  → minor
  BREAKING CHANGE / type!: → major
An explicit override in any subject wins:
  [release major] / [release minor] / [release patch]
  or a subject/body line `release-as: vX.Y.Z`

With no existing v* tag, the next release is always v0.1.0.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from typing import Iterable, List, Optional, Sequence, Tuple

TAG_RE = re.compile(r"^v(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})$")
OVERRIDE_RE = re.compile(r"\[release\s+(major|minor|patch)\]", re.IGNORECASE)
RELEASE_AS_RE = re.compile(r"(?im)^\s*release-as:\s*(v\d+\.\d+\.\d+)\s*$")
BREAKING_FOOTER_RE = re.compile(r"(?im)^\s*BREAKING CHANGE(?:S)?:")
CONVENTIONAL_RE = re.compile(
    r"^(?P<type>feat|fix|perf|refactor|docs|test|build|ci|chore|style|revert)"
    r"(?:\([^)]*\))?(?P<break>!)?:\s*"
)


@dataclass(frozen=True)
class Version:
    major: int
    minor: int
    patch: int

    @classmethod
    def parse(cls, tag: str) -> "Version":
        match = TAG_RE.match(tag.strip())
        if not match:
            raise ValueError(f"Not a Hanami release tag: {tag}")
        return cls(int(match.group(1)), int(match.group(2)), int(match.group(3)))

    def bump(self, kind: str) -> "Version":
        if kind == "major":
            return Version(self.major + 1, 0, 0)
        if kind == "minor":
            return Version(self.major, self.minor + 1, 0)
        if kind == "patch":
            return Version(self.major, self.minor, self.patch + 1)
        raise ValueError(f"Unknown bump: {kind}")

    def tag(self) -> str:
        return f"v{self.major}.{self.minor}.{self.patch}"

    def code(self) -> int:
        return self.major * 1_000_000 + self.minor * 1_000 + self.patch

    def name(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


def highest_semver_tag(tags: Iterable[str]) -> Optional[str]:
    best: Optional[Version] = None
    best_tag: Optional[str] = None
    for tag in tags:
        try:
            parsed = Version.parse(tag)
        except ValueError:
            continue
        if best is None or (parsed.major, parsed.minor, parsed.patch) > (
            best.major,
            best.minor,
            best.patch,
        ):
            best = parsed
            best_tag = parsed.tag()
    return best_tag


def classify_message(subject: str, body: str = "") -> str:
    """Classify one commit. Overrides are returned as override:KIND or as:TAG."""
    text = f"{subject}\n{body}"
    release_as = RELEASE_AS_RE.search(text)
    if release_as:
        return f"as:{Version.parse(release_as.group(1)).tag()}"
    override = OVERRIDE_RE.search(text)
    if override:
        return f"override:{override.group(1).lower()}"
    if BREAKING_FOOTER_RE.search(body) or BREAKING_FOOTER_RE.search(subject):
        return "major"
    match = CONVENTIONAL_RE.match(subject.strip())
    if match:
        if match.group("break"):
            return "major"
        if match.group("type") == "feat":
            return "minor"
    return "patch"


def decide_bump(messages: Sequence[Tuple[str, str]]) -> str:
    """Return major|minor|patch|as:vX.Y.Z from commit messages.

    Explicit release-as / [release …] overrides win over conventional commits.
    Among conventional commits, the highest bump in the range wins.
    """
    as_tag: Optional[str] = None
    override: Optional[str] = None
    rank = {"patch": 0, "minor": 1, "major": 2}
    chosen = "patch"
    for subject, body in messages:
        kind = classify_message(subject, body)
        if kind.startswith("as:"):
            as_tag = kind.split(":", 1)[1]
            continue
        if kind.startswith("override:"):
            override = kind.split(":", 1)[1]
            continue
        if rank[kind] > rank[chosen]:
            chosen = kind
    if as_tag is not None:
        return f"as:{as_tag}"
    if override is not None:
        return override
    return chosen


def next_version(latest_tag: Optional[str], messages: Sequence[Tuple[str, str]]) -> Version:
    if latest_tag is None:
        return Version.parse("v0.1.0")
    current = Version.parse(latest_tag)
    decision = decide_bump(messages)
    if decision.startswith("as:"):
        return Version.parse(decision.split(":", 1)[1])
    return current.bump(decision)


def git_tags() -> List[str]:
    out = subprocess.check_output(
        ["git", "tag", "--list", "v[0-9]*.[0-9]*.[0-9]*"],
        text=True,
    )
    return [line.strip() for line in out.splitlines() if line.strip()]


def git_messages_since(tag: Optional[str]) -> List[Tuple[str, str]]:
    args = ["git", "log", "--no-merges", "--pretty=format:%s%x00%b%x1e"]
    if tag:
        args.append(f"{tag}..HEAD")
    else:
        args.extend(["-n", "50"])
    raw = subprocess.check_output(args, text=True)
    messages: List[Tuple[str, str]] = []
    for entry in raw.split("\x1e"):
        entry = entry.strip("\n")
        if not entry:
            continue
        subject, _, body = entry.partition("\x00")
        messages.append((subject.strip(), body.strip()))
    return messages


def self_test() -> None:
    assert Version.parse("v0.1.0").code() == 1000
    assert Version.parse("v1.2.3").code() == 1_002_003
    assert Version.parse("v0.1.0").bump("patch").tag() == "v0.1.1"
    assert Version.parse("v0.1.0").bump("minor").tag() == "v0.2.0"
    assert Version.parse("v0.1.9").bump("major").tag() == "v1.0.0"

    assert highest_semver_tag(["v0.1.0", "v0.2.0", "latest", "v0.1.9"]) == "v0.2.0"
    assert highest_semver_tag([]) is None
    assert highest_semver_tag(["not-a-tag"]) is None

    assert classify_message("fix: typo") == "patch"
    assert classify_message("feat: ask from shade") == "minor"
    assert classify_message("feat(tools)!: new schema") == "major"
    assert classify_message("fix: volume", "BREAKING CHANGE: stream names") == "major"
    assert classify_message("chore: release [release minor]") == "override:minor"
    assert classify_message("docs: note", "release-as: v3.0.0") == "as:v3.0.0"

    assert decide_bump([("fix: a", ""), ("feat: b", "")]) == "minor"
    assert decide_bump([("feat: a", ""), ("fix!: b", "")]) == "major"
    assert decide_bump([("feat: a", ""), ("chore: x [release patch]", "")]) == "patch"
    assert decide_bump([("feat: a", "release-as: v9.1.0")]) == "as:v9.1.0"
    assert decide_bump([("fix: a", ""), ("chore: pin [release major]", "")]) == "major"

    assert next_version(None, [("feat!: huge", "")]).tag() == "v0.1.0"
    assert next_version("v0.1.0", [("fix: x", "")]).tag() == "v0.1.1"
    assert next_version("v0.1.0", [("feat: x", "")]).tag() == "v0.2.0"
    assert next_version("v0.2.3", [("fix!: x", "")]).tag() == "v1.0.0"
    assert next_version("v0.2.3", [("chore: pin", "release-as: v0.3.0")]).tag() == "v0.3.0"
    print("next-hanami-version.py: self-test ok")


def main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument(
        "--latest-tag",
        help="Override the latest tag discovery (empty string means no tag).",
    )
    parser.add_argument(
        "--print",
        dest="fields",
        action="append",
        choices=("tag", "versionName", "versionCode", "bump", "previous"),
        help="Print selected fields as KEY=VALUE lines. Default: all.",
    )
    args = parser.parse_args(list(argv))
    if args.self_test:
        self_test()
        return 0

    if args.latest_tag is not None:
        previous = args.latest_tag.strip() or None
        if previous is not None:
            previous = Version.parse(previous).tag()
    else:
        previous = highest_semver_tag(git_tags())

    messages = git_messages_since(previous)
    decision = "first" if previous is None else decide_bump(messages)
    nxt = next_version(previous, messages)
    bump = decision if not decision.startswith("as:") else "release-as"

    selected = args.fields or ["tag", "versionName", "versionCode", "bump", "previous"]
    mapping = {
        "tag": nxt.tag(),
        "versionName": nxt.name(),
        "versionCode": str(nxt.code()),
        "bump": bump if previous is not None else "first",
        "previous": previous or "",
    }
    for key in selected:
        print(f"{key}={mapping[key]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
