import importlib.util
from pathlib import Path
import hashlib
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release_module', Path(__file__).with_name('publish_release.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class OfficialReleaseTests(unittest.TestCase):
    def artifact(self, folder, version='2.1.0-261119'):
        apk = Path(folder) / f'CardFace-{version}.apk'
        apk.write_bytes(b'test-apk' * 300)
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        apk.with_suffix('.apk.sha256').write_text(f'{digest}  {apk.name}\n')
        (Path(folder) / 'test-summary.txt').write_text('Tests=122, failures=0, errors=0, skipped=0\n')

    def test_version_format(self):
        for v in ('2.1.0-261119', '2.1.0-2611191', '2.1.0-2611192', '2.1.0-26111910'):
            self.assertIsNotNone(m.VERSION.fullmatch(v))
        for v in ('2.1.0-261119.1', '2.1.0-oneplus.1', '2.1.0-26111901'):
            self.assertIsNone(m.VERSION.fullmatch(v))

    def test_collision_with_existing_release(self):
        import sys
        sys.path.insert(0, str(Path(__file__).parent))
        with tempfile.TemporaryDirectory() as folder:
            self.artifact(folder)
            release = {'tag_name': 'v2.1.0-261119', 'draft': False}
            def fake(path, *args, **kwargs):
                if path.startswith('/releases?'): return [release]
                if path.startswith('/commits/'): return {'sha': 'a' * 40}
                raise AssertionError(path)
            with patch.object(m, 'api', side_effect=fake):
                with self.assertRaisesRegex(RuntimeError, 'incomplete'):
                    m.publish('2.1.0-261119', 'a' * 40, folder)

    def test_existing_complete_release_is_untouched(self):
        version = '2.1.0-261119'
        assets = [{'name': f'CardFace-{version}.apk', 'state': 'uploaded', 'size': 1000},
                  {'name': f'CardFace-{version}.apk.sha256', 'state': 'uploaded', 'size': 90}]
        release = {'tag_name': 'v' + version, 'draft': False, 'prerelease': False,
                   'assets': assets, 'html_url': 'https://example.invalid/release'}
        def fake(path, *args, **kwargs):
            if path.startswith('/releases?'): return [release]
            if path.startswith('/commits/'): return {'sha': 'a' * 40}
            raise AssertionError((path, args))
        with tempfile.TemporaryDirectory() as folder:
            self.artifact(folder)
            with patch.object(m, 'api', side_effect=fake):
                self.assertEqual(m.publish(version, 'a' * 40, folder), release['html_url'])

    def test_checked_artifact(self):
        with tempfile.TemporaryDirectory() as folder:
            self.artifact(folder)
            self.assertEqual(m.checked_artifact(folder, '2.1.0-261119')[0].name, 'CardFace-2.1.0-261119.apk')

    def test_failed_tests_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            self.artifact(folder)
            (Path(folder) / 'test-summary.txt').write_text('Tests=122, failures=1, errors=0, skipped=0\n')
            with self.assertRaises(ValueError):
                m.checked_artifact(folder, '2.1.0-261119')

    def test_prerelease_is_not_officially_ready(self):
        release = {'draft': False, 'prerelease': True, 'assets': []}
        self.assertFalse(m.ready(release, '2.1.0-261119'))

    def test_official_requires_both_assets(self):
        version = '2.1.0-261119'
        release = {'draft': False, 'prerelease': False, 'assets': [
            {'name': f'CardFace-{version}.apk', 'state': 'uploaded', 'size': 1000}]}
        self.assertFalse(m.ready(release, version))
        release['assets'].append({'name': f'CardFace-{version}.apk.sha256', 'state': 'uploaded', 'size': 95})
        self.assertTrue(m.ready(release, version))


if __name__ == '__main__':
    unittest.main()
