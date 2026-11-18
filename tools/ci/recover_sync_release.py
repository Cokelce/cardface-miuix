#!/usr/bin/env python3
"""Locate the exact synchronized commit when its publication needs recovery."""
import json
import os
import re
import subprocess
import publish_release as release


def main():
    state = json.load(open('.github/upstream-state.json'))
    version = state['version']
    if not release.VERSION.fullmatch(version):
        raise ValueError('Invalid stored version')
    sha = subprocess.check_output(['git', 'log', '-1', '--format=%H', '--', '.github/upstream-state.json'], text=True).strip()
    message = subprocess.check_output(['git', 'show', '-s', '--format=%s', sha], text=True).strip()
    if not re.fullmatch(r'[0-9a-f]{40}', sha) or message != f'chore: sync upstream and adapt OnePlus as {version}':
        print('No automatically synced release to recover')
        return
    original = json.loads(subprocess.check_output(['git', 'show', f'{sha}:.github/upstream-state.json'], text=True))
    if original != state:
        raise RuntimeError('Sync state differs from its recorded commit')
    page = 1
    found = None
    while True:
        items = release.api(f'/releases?per_page=100&page={page}') or []
        found = next((x for x in items if x.get('tag_name') == 'v' + version), None)
        if found or len(items) < 100: break
        page += 1
    if found and not found.get('draft', True):
        if not release.ready(found, version):
            raise RuntimeError('Published sync release is incomplete; preserved for review')
        print('Sync release already published')
        return
    with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
        out.write(f'recover=true\nsha={sha}\nversion={version}\n')
    print(f'Recovery needed: {version} at {sha}')

if __name__ == '__main__':
    main()
