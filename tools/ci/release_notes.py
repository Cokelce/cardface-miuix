#!/usr/bin/env python3
"""Build concise release notes with only upstream and OnePlus and OPPO wallet adaptation changes."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CURATED = ROOT / '.github' / 'release-notes.json'

def notes(version: str) -> str:
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-oneplus\.\d+|-\d{6}(?:[1-9]\d*)?)', version):
        raise ValueError('版本格式无效')
    data = json.loads(CURATED.read_text(encoding='utf-8'))
    entry = data.get(version)
    if entry is None:
        raise ValueError(f'没有已审核的 {version} 更新日志条目')
    upstream = [x.strip() for x in entry['upstream_changes'] if x.strip()]
    adaptation = [x.strip() for x in entry['adaptation_changes'] if x.strip()]
    if not upstream or not adaptation:
        raise ValueError('上游更新和OnePlus 和 OPPO 钱包适配说明均须填写')
    return '\n'.join([
        '## 上游更新', '', *[f'- {x}' for x in upstream], '',
        '## OnePlus 和 OPPO 钱包适配', '', *[f'- {x}' for x in adaptation], ''
    ])

if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    print(notes(args.version), end='')
