import sys
from pathlib import Path as _Path
sys.path.insert(0, str(_Path(__file__).resolve().parent))
"""Offline git/merge integration tests; only local temporary remotes, no GitHub writes."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

DEPLOY = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('sync_git_test', Path(__file__).with_name('sync_upstream.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def git(root, *args):
    p = subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=True)
    if p.returncode:
        raise AssertionError(p.stderr)
    return p.stdout.strip()


class GitSyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='oneplus-sync-')
        self.folder = Path(self.temp.name)
        self.root = self.folder / 'work'
        self.root.mkdir()
        git(self.root, 'init', '-q', '-b', 'main')
        git(self.root, 'config', 'user.name', 'CI')
        git(self.root, 'config', 'user.email', 'ci@example.invalid')
        files = {
            'app/build.gradle.kts': 'applicationId = "io.github.miambao.cardface"\nversionCode = 20\nversionName = "2.0.0"\n',
            'app/src/main/kotlin/com/dsh/miuix/cardface/core/BuildInfo.kt': 'INTERNAL_VERSION = "2.0.0.20"\n',
            'app/src/main/kotlin/com/dsh/miuix/cardface/core/Updates.kt': 'REPOSITORY = "MiamBao/cardface-miuix"\n',
            'app/src/main/kotlin/com/dsh/miuix/cardface/ui/InfoScreens.kt': 'OnePlus 15 / ColorOS 17（LSPosed 2.2.1）\nOnePlus 15 / ColorOS 17（LSPosed 2.2.1）\nhttps://github.com/Cokelce/cardface-miuix\n',
            'app/src/main/kotlin/com/dsh/miuix/cardface/ui/EditorScreen.kt': '从相册选 粘贴网址 从图库选 在线卡面\n',
            'app/src/main/kotlin/com/dsh/miuix/cardface/xposed/HookEngine.kt': 'fun hook() = "upstream-base"\n',
            'app/src/main/AndroidManifest.xml': '<manifest><queries><package android:name="com.finshell.wallet" /></queries></manifest>',
            'app/src/main/resources/META-INF/xposed/scope.list': 'com.miui.tsmclient\n',
            'README.md': 'upstream readme\n', 'LICENSE': 'GPL-3.0\n',
            'build.gradle.kts': '// toolchain\n', 'settings.gradle.kts': '// repositories\n',
            'gradle.properties': 'android.useAndroidX=true\n', 'gradlew': '#!/bin/sh\n',
            'gradle/wrapper/gradle-wrapper.properties': 'distributionUrl=gradle.zip\n',
            '.github/workflows/sync.yml': '# trusted workflow\n', '.github/release-notes.json': json.dumps({'2.0.0-261118': {'upstream_changes':['基线更新。'], 'adaptation_changes':['一加钱包适配。']}}), '.gitignore': '__pycache__/\n',
        }
        for path, text in files.items():
            self.put(path, text)
        for name in ('sync_upstream.py', 'check_oneplus.py', 'publish_prerelease.py', 'publish_release.py'):
            self.put('tools/ci/' + name, (DEPLOY / 'tools/ci' / name).read_text())
        git(self.root, 'add', '-A')
        git(self.root, 'commit', '-qm', 'synthetic upstream base')
        self.base = git(self.root, 'rev-parse', 'HEAD')
        self.origin = self.folder / 'origin.git'
        self.upstream = self.folder / 'upstream.git'
        for remote in (self.origin, self.upstream):
            subprocess.run(['git', 'init', '--bare', '-q', str(remote)], check=True)
        git(self.root, 'remote', 'add', 'upstream', str(self.upstream))
        git(self.root, 'push', '-q', 'upstream', 'HEAD:main')
        self.put('app/build.gradle.kts', files['app/build.gradle.kts'].replace('io.github.miambao.cardface"', 'io.github.cardface.one"').replace('versionCode = 20', 'versionCode = 51').replace('"2.0.0"', '"2.0.0-261118"'))
        self.put('app/src/main/kotlin/com/dsh/miuix/cardface/core/BuildInfo.kt', 'INTERNAL_VERSION = "2.0.0.51"\n')
        self.put('app/src/main/kotlin/com/dsh/miuix/cardface/core/Updates.kt', 'REPOSITORY = "Cokelce/cardface-miuix"\n')
        self.put('app/src/main/resources/META-INF/xposed/scope.list', 'com.finshell.wallet\n')
        self.put('app/src/main/kotlin/com/dsh/miuix/cardface/xposed/HookEngine.kt', 'fun hook() = "oneplus-fork"\n')
        self.put('.github/upstream-state.json', json.dumps({'commit': self.base, 'base_version': '2.0.0', 'suffix': 5, 'version': '2.0.0-261118'}))
        git(self.root, 'add', '-A')
        git(self.root, 'commit', '-qm', 'synthetic OnePlus fork')
        git(self.root, 'remote', 'add', 'origin', str(self.origin))
        git(self.root, 'push', '-q', 'origin', 'HEAD:main')
        self.main = git(self.root, 'rev-parse', 'HEAD')
        git(self.root, 'fetch', '-q', 'origin', 'main')
        git(self.root, 'fetch', '-q', 'upstream', 'main')
        self.out = self.folder / 'outputs'
        self.calls = []

    def put(self, rel, text):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def tearDown(self):
        self.temp.cleanup()

    def change_upstream(self, changes):
        git(self.root, 'checkout', '-q', '--detach', 'upstream/main')
        for rel, value in changes.items():
            self.put(rel, value)
        git(self.root, 'add', '-A')
        git(self.root, 'commit', '-qm', 'synthetic upstream change')
        sha = git(self.root, 'rev-parse', 'HEAD')
        git(self.root, 'push', '-q', 'upstream', 'HEAD:main')
        git(self.root, 'checkout', '-q', 'main')
        return sha

    def fake_api(self, verb, path, payload=None):
        self.calls.append((verb, path, payload))
        if verb == 'GET':
            if path.startswith('/releases'):
                return getattr(self, 'releases', [])
            return [{'html_url': 'https://example.invalid/pr/1'}] if path.startswith('/pulls') else []
        return {'number': 1}

    def prepare(self):
        core = self.root / 'app/src/main/kotlin/com/dsh/miuix/cardface/core'
        with patch.object(m, 'china_date', return_value='261119'), patch.object(m, 'ROOT', self.root), patch.object(m, 'STATE', self.root / '.github/upstream-state.json'), patch.object(m, 'APP_GRADLE', self.root / 'app/build.gradle.kts'), patch.object(m, 'BUILD_INFO', core / 'BuildInfo.kt'), patch.object(m, 'api', self.fake_api), patch.dict(os.environ, {'GITHUB_OUTPUT': str(self.out), 'GITHUB_REPOSITORY': 'local/test', 'GH_TOKEN': 'local-only'}):
            return m.prepare()

    def test_no_change(self):
        self.assertEqual(self.prepare(), 0)
        self.assertEqual(self.out.read_text(), 'changed=false\n')

    def test_readme_change(self):
        sha = self.change_upstream({'README.md': 'updated upstream\n', '.github/workflows/sync.yml': '# untrusted upstream automation\n'})
        self.assertEqual(self.prepare(), 0)
        self.assertIn('changed=true', self.out.read_text())
        self.assertIn('updated upstream', (self.root / 'README.md').read_text())
        self.assertTrue((self.root / 'README.md').read_text().startswith('> 本分支为 **OnePlus 和 OPPO 钱包适配版**'))
        self.assertIn('version=2.0.0-261119', self.out.read_text())
        self.assertEqual((self.root / '.github/workflows/sync.yml').read_text(), '# trusted workflow\n')
        self.assertIn('versionCode = 52', (self.root / 'app/build.gradle.kts').read_text())
        self.assertEqual(json.loads((self.root / '.github/upstream-state.json').read_text())['commit'], sha)

    def test_latest_fork_readme_wins_even_on_conflict(self):
        custom = '小米智能卡、一加钱包\nhttps://github.com/Cokelce/cardface-miuix/releases/latest\n'
        self.put('README.md', custom)
        git(self.root, 'add', 'README.md')
        git(self.root, 'commit', '-qm', 'user updates fork readme')
        git(self.root, 'push', '-q', 'origin', 'HEAD:main')
        self.change_upstream({'README.md': 'upstream replacement without fork customizations\n'})
        self.assertEqual(self.prepare(), 0)
        synced_readme = (self.root / 'README.md').read_text()
        self.assertIn('upstream replacement without fork customizations', synced_readme)
        self.assertTrue(synced_readme.startswith('> 本分支为 **OnePlus 和 OPPO 钱包适配版**'))

    def test_latest_owner_code_is_applied_without_upstream_merge_parent(self):
        adaptation = 'app/src/main/kotlin/com/dsh/miuix/cardface/xposed/OwnerAddedHook.kt'
        self.put(adaptation, 'fun latestOwnerHook() = "owner-submitted-later"\n')
        git(self.root, 'add', adaptation)
        git(self.root, 'commit', '-qm', 'owner uploads another OnePlus adapter')
        owner_tip = git(self.root, 'rev-parse', 'HEAD')
        git(self.root, 'push', '-q', 'origin', 'HEAD:main')
        upstream_new = 'app/src/main/kotlin/com/dsh/miuix/cardface/ui/UpstreamNewFeature.kt'
        upstream_tip = self.change_upstream({upstream_new: 'fun upstreamFeature() = true\n'})
        self.assertEqual(self.prepare(), 0)
        self.assertIn('owner-submitted-later', (self.root / adaptation).read_text())
        self.assertIn('upstreamFeature', (self.root / upstream_new).read_text())
        self.assertFalse((self.root / '.git/MERGE_HEAD').exists())
        git(self.root, 'add', '-A')
        git(self.root, 'commit', '-qm', 'snapshot import and owner adaptation')
        parents = git(self.root, 'show', '-s', '--format=%P', 'HEAD').split()
        self.assertEqual(parents, [owner_tip])
        self.assertNotIn(upstream_tip, parents)
        self.assertEqual(git(self.root, 'ls-remote', 'upstream', 'refs/heads/main').split()[0], upstream_tip)

    def test_owner_build_dependencies_survive_snapshot_import(self):
        rel = 'app/build.gradle.kts'
        self.put(rel, (self.root / rel).read_text() + '\n// owner-custom-dependency\n')
        git(self.root, 'add', rel)
        git(self.root, 'commit', '-qm', 'owner adds build dependency')
        git(self.root, 'push', '-q', 'origin', 'HEAD:main')
        self.change_upstream({'README.md': 'next upstream docs\n'})
        self.assertEqual(self.prepare(), 0)
        self.assertIn('owner-custom-dependency', (self.root / rel).read_text())
        self.assertIn('io.github.cardface.one', (self.root / rel).read_text())

    def test_upstream_app_version_conflict(self):
        self.change_upstream({'app/build.gradle.kts': 'applicationId = "io.github.miambao.cardface"\nversionCode = 25\nversionName = "2.1.0"\n// upstream-new-dependency\n', 'app/src/main/kotlin/com/dsh/miuix/cardface/core/BuildInfo.kt': 'INTERNAL_VERSION = "2.1.0.25"\n'})
        self.assertEqual(self.prepare(), 0)
        text = (self.root / 'app/build.gradle.kts').read_text()
        self.assertIn('versionName = "2.1.0-261119"', text)
        self.assertIn('versionCode = 52', text)
        self.assertIn('upstream-new-dependency', text)

    def test_business_conflict_aborts(self):
        self.change_upstream({'app/src/main/kotlin/com/dsh/miuix/cardface/xposed/HookEngine.kt': 'fun hook() = "changed-upstream-hook"\n'})
        before = (self.root / '.github/upstream-state.json').read_bytes()
        self.assertEqual(self.prepare(), 2)
        self.assertEqual(git(self.root, 'rev-parse', 'HEAD'), self.main)
        self.assertEqual((self.root / '.github/upstream-state.json').read_bytes(), before)
        self.assertTrue(any(verb == 'POST' and path == '/issues' for verb, path, _ in self.calls))

    def existing_adaptation(self):
        sha = self.change_upstream({'README.md': 'another update\n'})
        branch = 'automation/upstream-' + sha[:12]
        git(self.root, 'checkout', '-q', '-b', branch)
        self.put('.github/upstream-state.json', json.dumps({'commit': sha, 'base_version': '2.0.0', 'suffix': 6, 'version': '2.0.0-261119'}))
        self.put('app/build.gradle.kts', (self.root / 'app/build.gradle.kts').read_text().replace('261118', '261119').replace('versionCode = 51', 'versionCode = 52'))
        git(self.root, 'add', '-A')
        git(self.root, 'commit', '-qm', 'previously prepared adaptation')
        commit = git(self.root, 'rev-parse', 'HEAD')
        git(self.root, 'push', '-q', 'origin', f'HEAD:refs/heads/{branch}')
        git(self.root, 'checkout', '-q', 'main')
        git(self.root, 'branch', '-D', branch)
        return sha, branch, commit

    def test_old_candidate_is_rebuilt_from_main_without_force(self):
        sha, branch, commit = self.existing_adaptation()
        self.assertEqual(self.prepare(), 0)
        self.assertEqual(git(self.root, 'ls-remote', 'origin', f'refs/heads/{branch}').split()[0], commit)
        self.assertIn('changed=true', self.out.read_text())
        self.assertIn('version=2.0.0-261119', self.out.read_text())
        self.assertEqual(json.loads((self.root / '.github/upstream-state.json').read_text())['commit'], sha)

    def test_old_candidate_uses_latest_owner_adapter(self):
        _, _, _ = self.existing_adaptation()
        rel = 'app/src/main/kotlin/com/dsh/miuix/cardface/xposed/LaterOwnerHook.kt'
        self.put(rel, 'fun laterOwnerHook() = "latest"\n')
        git(self.root, 'add', rel)
        git(self.root, 'commit', '-qm', 'owner adapter added after candidate creation')
        git(self.root, 'push', '-q', 'origin', 'HEAD:main')
        self.assertEqual(self.prepare(), 0)
        self.assertIn('laterOwnerHook', (self.root / rel).read_text())

    def test_existing_release_forces_fresh_suffix(self):
        _, branch, commit = self.existing_adaptation()
        self.releases = [{'tag_name': 'v2.0.0-261119', 'draft': False, 'assets': [
            {'name': 'CardFace-2.0.0-261119.apk', 'state': 'uploaded', 'size': 1200},
            {'name': 'CardFace-2.0.0-261119.apk.sha256', 'state': 'uploaded', 'size': 95}]}]
        self.assertEqual(self.prepare(), 0)
        self.assertIn('changed=true', self.out.read_text())
        self.assertIn('version=2.0.0-2611191', self.out.read_text())
        self.assertEqual(git(self.root, 'ls-remote', 'origin', f'refs/heads/{branch}').split()[0], commit)

    def test_unmerged_published_tag_suffix_is_incremented(self):
        self.change_upstream({'README.md': 'third update\n'})
        git(self.root, 'tag', 'v2.0.0-2611198')
        git(self.root, 'push', '-q', 'origin', 'v2.0.0-2611198')
        self.assertEqual(self.prepare(), 0)
        self.assertIn('version=2.0.0-2611199', self.out.read_text())


if __name__ == '__main__':
    unittest.main(verbosity=2)
