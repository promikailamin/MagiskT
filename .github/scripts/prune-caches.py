#!/usr/bin/env python3
"""Prune stale GitHub Actions caches for the Magisk build.

Caches are grouped into families by their key prefix (e.g. "sccache-macOS",
"gradle-build-cache-macOS"). For every family the newest ``KEEP`` caches are
kept and the rest are deleted, so the total cache quota stays bounded even
though keys change whenever the underlying source changes.

Requires: GH_TOKEN (or GITHUB_TOKEN) and the gh CLI (preinstalled on runners).
"""
import os
import subprocess
import sys

REPO = os.environ["GITHUB_REPOSITORY"]
KEEP = int(os.environ.get("KEEP_CACHES", "3"))


def gh(*args: str) -> str:
    proc = subprocess.run(
        ["gh", *args], check=True, capture_output=True, text=True
    )
    return proc.stdout


def main() -> int:
    lines = gh(
        "api",
        "--paginate",
        f"repos/{REPO}/actions/caches?per_page=100",
        "--jq",
        ".actions_caches[] | [.id,.key] | @tsv",
    ).strip().splitlines()

    groups: dict[str, list[tuple[int, str]]] = {}
    for line in lines:
        fields = line.split("\t")
        if len(fields) != 2:
            continue
        cache_id, key = int(fields[0]), fields[1]
        family = "-".join(key.split("-")[:2])
        groups.setdefault(family, []).append((cache_id, key))

    deleted = 0
    for family, entries in groups.items():
        entries.sort(reverse=True)
        for cache_id, key in entries[KEEP:]:
            gh("api", "-X", "DELETE", f"repos/{REPO}/actions/caches/{cache_id}")
            print(f"deleted: {key}")
            deleted += 1

    print(f"pruned {deleted} cache(s), kept <= {KEEP} per family")
    return 0


if __name__ == "__main__":
    sys.exit(main())
