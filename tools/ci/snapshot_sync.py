#!/usr/bin/env python3
"""Run upstream snapshot import with a direct owner-delta patch.

The owner adaptation is the diff from the recorded upstream base to the
current owner main tree.  This avoids the temporary-index path that failed
when upstream changed the same files in 2.1.0.
"""
from __future__ import annotations
import subprocess
import sys
from pathlib import Path

import sync_upstream as sync

ROOT = sync.ROOT
TRUSTED = sync.TRUSTED_PATHS
CONFIG = set(sync.UPSTREAM_CONFIG)


def git(*args: str, check: bool = True) -> str:
    p = subprocess.run(["git", *args], cwd=ROOT, text=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and p.returncode:
        raise RuntimeError(f"git {' '.join(args)} failed: {p.stderr.strip()}")
    return p.stdout


def pathspecs():
    result = ["."]
    for path in TRUSTED:
        result.append(f":(exclude){path}")
    for path in CONFIG:
        result.append(f":(exclude){path}")
    return result


def import_snapshot(old: str, sha: str) -> bool:
    # Capture only the owner's changes relative to the recorded upstream base.
    # README, CI, release metadata and build identity are restored from main.
    patch = subprocess.run(
        ["git", "diff", "--binary", old, "origin/main", "--", *pathspecs()],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    if patch.returncode:
        raise RuntimeError(patch.stderr.decode(errors="replace"))

    git("read-tree", "--reset", "-u", sha)
    git("restore", "--source=origin/main", "--staged", "--worktree", "--", *TRUSTED)

    if not patch.stdout:
        return True
    applied = subprocess.run(
        ["git", "apply", "--3way", "--index", "-"],
        input=patch.stdout, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    if applied.returncode:
        files = git("diff", "--name-only", "--diff-filter=U", check=False).splitlines()
        git("read-tree", "--reset", "-u", "origin/main")
        sync.report_conflict(sha, files or ["owner adaptation delta does not apply cleanly"])
        print("CONFLICT upstream=" + sha + " files=" + ",".join(files), file=sys.stderr)
        return False
    return True


sync.import_snapshot = import_snapshot

if __name__ == "__main__":
    try:
        raise SystemExit(sync.main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
