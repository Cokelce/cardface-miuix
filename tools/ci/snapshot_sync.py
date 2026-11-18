#!/usr/bin/env python3
"""Import upstream snapshot and overlay the latest owner-maintained adaptation."""
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
    return ["."] + [f":(exclude){p}" for p in [*TRUSTED, *CONFIG]]


def import_snapshot(old: str, sha: str) -> bool:
    # Capture the current fork's owner delta, excluding README/CI/version metadata.
    patch = subprocess.run(
        ["git", "diff", "--binary", old, "origin/main", "--", *pathspecs()],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    if patch.returncode:
        raise RuntimeError(patch.stderr.decode(errors="replace"))

    # Start from the exact upstream snapshot, then overlay the owner delta.
    git("read-tree", "--reset", "-u", sha)
    git("restore", "--source=origin/main", "--staged", "--worktree", "--", *TRUSTED)

    if not patch.stdout:
        return True
    applied = subprocess.run(
        ["git", "apply", "--3way", "--index", "-"], input=patch.stdout,
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    if applied.returncode:
        # The owner version wins only for files where upstream changed the same
        # lines; all non-conflicting upstream changes remain in the tree.
        conflicts = git("diff", "--name-only", "--diff-filter=U", check=False).splitlines()
        if conflicts:
            subprocess.run(["git", "checkout", "--theirs", """--""", *conflicts], cwd=ROOT, check=True)
            git("add", "--", *conflicts)
        remaining = git("diff", "--name-only", "--diff-filter=U", check=False).splitlines()
        if remaining:
            sync.report_conflict(sha, remaining)
            git("read-tree", "--reset", "-u", "origin/main")
            return False
        print("OWNER_OVERLAY_CONFLICTS=" + ",".join(conflicts), file=sys.stderr)
    return True


sync.import_snapshot = import_snapshot

if __name__ == "__main__":
    try:
        raise SystemExit(sync.main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
