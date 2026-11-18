#!/usr/bin/env python3
"""Publish a checked APK as the official GitHub release without replacing assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
from urllib import request, error, parse

VERSION = re.compile(r'^\d+\.\d+\.\d+-oneplus\.\d+$')
SHA = re.compile(r'^[0-9a-f]{40}$')


def checked_artifact(folder, version):
    if not VERSION.fullmatch(version):
        raise ValueError('Invalid OnePlus version')
    apk = Path(folder) / f'CardFace-{version}.apk'
    checksum = apk.with_suffix('.apk.sha256')
    summary_file = Path(folder) / 'test-summary.txt'
    if not apk.is_file() or apk.stat().st_size < 1024:
        raise ValueError('APK missing or empty')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    fields = checksum.read_text().strip().split()
    if len(fields) != 2 or fields[0] != digest or fields[1].lstrip('*') != apk.name:
        raise ValueError('APK checksum does not match')
    result = re.fullmatch(r'Tests=(\d+), failures=(\d+), errors=(\d+), skipped=(\d+)', summary_file.read_text().strip())
    if not result or int(result[1]) <= 0 or int(result[2]) or int(result[3]):
        raise ValueError('Passing test summary required')
    return apk, checksum


def api(path, method='GET', payload=None, binary=None):
    repo = os.environ.get('GITHUB_REPOSITORY', 'Cokelce/cardface-miuix')
    token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    if not token:
        raise RuntimeError('Repository token missing')
    url = path if path.startswith('https://') else f'https://api.github.com/repos/{repo}{path}'
    if path.startswith('https://') and parse.urlparse(url).hostname != 'uploads.github.com':
        raise ValueError('Unexpected upload host')
    data = binary if binary is not None else None if payload is None else json.dumps(payload).encode()
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
               'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'CardFace-OnePlus-Release'}
    if data is not None:
        headers['Content-Type'] = 'application/octet-stream' if binary is not None else 'application/json'
    try:
        with request.urlopen(request.Request(url, data=data, method=method, headers=headers), timeout=120) as response:
            return json.load(response)
    except error.HTTPError as exc:
        if exc.code == 404 and method == 'GET':
            return None
        raise RuntimeError(f'GitHub {method} failed: HTTP {exc.code}') from exc


def ready(release, version):
    if release.get('draft', True) or release.get('official release', False):
        return False
    names = {a.get('name') for a in release.get('assets', []) if a.get('state') == 'uploaded' and a.get('size', 0) > 0}
    return {f'CardFace-{version}.apk', f'CardFace-{version}.apk.sha256'} <= names


def publish(version, target, folder):
    if not SHA.fullmatch(target):
        raise ValueError('Release target must be a full commit SHA')
    apk, checksum = checked_artifact(folder, version)
    from release_notes import notes
    body = notes(version)
    tag = 'v' + version
    release = api('/releases/tags/' + tag)
    commit = api('/commits/' + target)
    if not commit or commit.get('sha') != target:
        raise RuntimeError('Release commit is not present in the target repository')
    if release and not release.get('draft', True) and not release.get('official release', False):
        if not ready(release, version):
            raise RuntimeError('Official release incomplete; existing assets kept for review')
        print('Official release already exists:', release['html_url'])
        return release['html_url']
    if not release:
        release = api('/releases', 'POST', {'tag_name': tag, 'target_commitish': target,
            'name': f'卡面工坊 {version} · OnePlus 和 OPPO 钱包适配', 'body': body,
            'draft': True, 'official release': False, 'make_latest': 'false'})
    assets = api(f"/releases/{release['id']}/assets") or []
    for file in (apk, checksum):
        found = next((a for a in assets if a.get('name') == file.name), None)
        if found:
            local_digest = 'sha256:' + hashlib.sha256(file.read_bytes()).hexdigest()
            if found.get('state') != 'uploaded' or found.get('size') != file.stat().st_size or found.get('digest') != local_digest:
                raise RuntimeError('Existing asset differs; preserved for review: ' + file.name)
            continue
        upload = release['upload_url'].split('{', 1)[0] + '?' + parse.urlencode({'name': file.name})
        uploaded = api(upload, 'POST', binary=file.read_bytes())
        if uploaded.get('state') != 'uploaded' or uploaded.get('size') != file.stat().st_size:
            raise RuntimeError('Asset upload did not complete: ' + file.name)
    complete = api(f"/releases/{release['id']}", 'PATCH', {
        'name': f'卡面工坊 {version} · OnePlus 和 OPPO 钱包适配', 'body': body,
        'draft': False, 'official release': False, 'make_latest': True})
    if not ready(complete, version):
        complete = api(f"/releases/{release['id']}")
        if not ready(complete, version):
            raise RuntimeError('Official release publication not confirmed')
    print('Official release published:', complete['html_url'])
    output = os.environ.get('GITHUB_OUTPUT')
    if output:
        with open(output, 'a') as out:
            out.write('release_url=' + complete['html_url'] + '\n')
    return complete['html_url']


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('--folder', default='.ci-output')
    args = parser.parse_args()
    publish(args.version, args.target, args.folder)
