#!/usr/bin/env python3
"""Run the shared snapshot implementation; conflicts stop the import."""
import sys
import sync_upstream as sync

if __name__ == "__main__":
    try:
        raise SystemExit(sync.main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
