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
    def artifact(self, folder, version='2.1.0-oneplus.1'):
        apk = Path(folder) / f'CardFace-{version}.apk'
        apk.write_bytes(b'test-apk' * 300)
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        apk.with_suffix('.apk.sha256').write_text(f'{digest}  {apk.name}\n')
        (Path(folder) / 'test-summary.txt').write_text('Tests=122, failures=0, errors=0, skipped=0\n')

    def test_checked_artifact(self):
        with tempfile.TemporaryDirectory() as folder:
            self.artifact(folder)
            self.assertEqual(m.checked_artifact(folder, '2.1.0-oneplus.1')[0].name, 'CardFace-2.1.0-oneplus.1.apk')

    def test_failed_tests_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            self.artifact(folder)
            (Path(folder) / 'test-summary.txt').write_text('Tests=122, failures=1, errors=0, skipped=0\n')
            with self.assertRaises(ValueError):
                m.checked_artifact(folder, '2.1.0-oneplus.1')

    def test_prerelease_is_not_officially_ready(self):
        release = {'draft': False, 'prerelease': True, 'assets': []}
        self.assertFalse(m.ready(release, '2.1.0-oneplus.1'))

    def test_official_requires_both_assets(self):
        version = '2.1.0-oneplus.1'
        release = {'draft': False, 'prerelease': False, 'assets': [
            {'name': f'CardFace-{version}.apk', 'state': 'uploaded', 'size': 1000}]}
        self.assertFalse(m.ready(release, version))
        release['assets'].append({'name': f'CardFace-{version}.apk.sha256', 'state': 'uploaded', 'size': 95})
        self.assertTrue(m.ready(release, version))


if __name__ == '__main__':
    unittest.main()
