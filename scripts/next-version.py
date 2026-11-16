#!/usr/bin/env python3
"""Version arithmetic for the release pipeline.

The development version in the POM is authoritative:

    release  1.0.3          patch  -> 1.0.4     (dev version was the released one)
    release  1.0.4-SNAPSHOT patch  -> 1.0.4     (dev version is already ahead)
    release  1.0.4-SNAPSHOT minor  -> 1.0.4     (never skips a version)
    snapshot 1.0.4                 -> 1.0.5-SNAPSHOT

Printing only, no file writes: the workflow does `mvn versions:set` with the value.
"""
from __future__ import annotations

import sys

BUMPS = ("major", "minor", "patch")


def _parts(version: str) -> list[int]:
    base = version.split("-", 1)[0]
    nums = [int(p) for p in base.split(".") if p.isdigit()]
    while len(nums) < 3:
        nums.append(0)
    return nums[:3]


def release_version(current: str, bump: str) -> str:
    """The version to publish for this run."""
    if bump not in BUMPS:
        raise SystemExit(f"unknown bump type: {bump} (expected one of {', '.join(BUMPS)})")
    if current.endswith("-SNAPSHOT"):
        # The POM already points at the unreleased version: publishing it needs no increment,
        # otherwise every release after a snapshot bump would skip a version number.
        return current.removesuffix("-SNAPSHOT")

    major, minor, patch = _parts(current)
    if bump == "major":
        return f"{major + 1}.0.0"
    if bump == "minor":
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def next_snapshot(released: str) -> str:
    """The development version to leave on the main branch after publishing."""
    major, minor, patch = _parts(released)
    return f"{major}.{minor}.{patch + 1}-SNAPSHOT"


def main(argv: list[str]) -> int:
    if len(argv) == 4 and argv[1] == "release":
        print(release_version(str(argv[2]).strip(), str(argv[3]).strip().lower()))
        return 0
    if len(argv) == 3 and argv[1] == "snapshot":
        print(next_snapshot(str(argv[2]).strip()))
        return 0
    print(__doc__.strip(), file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
