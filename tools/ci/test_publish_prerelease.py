import importlib.util
from pathlib import Path
import tempfile
import hashlib
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release_test_module', Path(__file__).with_name('publish_prerelease.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class PrereleaseTests(unittest.TestCase):
    def artifact(self, folder, version='2.0.1-oneplus.1'):
        apk = Path(folder) / f'CardFace-{version}.apk'
        apk.write_bytes(b'test-apk' * 200)
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        apk.with_suffix('.apk.sha256').write_text(f'{digest}  {apk.name}\n')
        (Path(folder) / 'test-summary.txt').write_text('Tests=117, failures=0, errors=0, skipped=0\n')
        return apk

    def test_retired_release_never_contacts_github(self):
        with patch.object(m, 'retired', return_value=True), patch.object(m, 'api') as api:
            self.assertIsNone(m.publish('2.0.1-oneplus.1', 'a' * 40, '/missing'))
            api.assert_not_called()

    def test_checked_apk(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = self.artifact(folder)
            self.assertEqual(m.checked_artifact(folder, '2.0.1-oneplus.1')[0], apk)

    def test_missing_or_changed_apk_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = self.artifact(folder)
            apk.write_bytes(apk.read_bytes() + b'changed')
            with self.assertRaises(ValueError):
                m.checked_artifact(folder, '2.0.1-oneplus.1')

    def test_failed_tests_are_not_publishable(self):
        with tempfile.TemporaryDirectory() as folder:
            self.artifact(folder)
            (Path(folder) / 'test-summary.txt').write_text('Tests=117, failures=1, errors=0, skipped=0\n')
            with self.assertRaises(ValueError):
                m.checked_artifact(folder, '2.0.1-oneplus.1')

    def test_publication_requires_apk_and_checksum(self):
        version = '2.0.1-oneplus.1'
        release = {'draft': False, 'assets': [{'name': f'CardFace-{version}.apk', 'state': 'uploaded', 'size': 1400}]}
        self.assertFalse(m.release_assets_ready(release, version))
        release['assets'].append({'name': f'CardFace-{version}.apk.sha256', 'state': 'uploaded', 'size': 95})
        self.assertTrue(m.release_assets_ready(release, version))
        release['draft'] = True
        self.assertFalse(m.release_assets_ready(release, version))

    def test_draft_uploaded_before_publishing(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = self.artifact(folder)
            uploaded = []
            calls = []
            def fake_api(path, method='GET', payload=None, binary=None):
                calls.append((path, method, payload))
                if path.startswith('/releases/tags/'):
                    return None
                if path.startswith('/commits/'):
                    return {'sha': 'a' * 40}
                if path == '/releases' and method == 'POST':
                    self.assertTrue(payload['draft'])
                    self.assertEqual(payload['name'], '卡面工坊 2.0.1-oneplus.1')
                    return {'id': 1, 'upload_url': 'https://uploads.github.com/repos/example/repo/releases/1/assets{?name}'}
                if path == '/releases/1/assets':
                    return uploaded
                if path.startswith('https://uploads.github.com'):
                    name = m.parse.parse_qs(m.parse.urlparse(path).query)['name'][0]
                    record = {'name': name, 'size': len(binary), 'state': 'uploaded', 'digest': 'sha256:' + hashlib.sha256(binary).hexdigest()}
                    uploaded.append(record)
                    return record
                if path == '/releases/1' and method == 'PATCH':
                    self.assertEqual(len(uploaded), 2)
                    self.assertEqual(payload['name'], '卡面工坊 2.0.1-oneplus.1')
                    self.assertTrue(payload['prerelease'])
                    self.assertFalse(payload['draft'])
                    return {'draft': False, 'assets': uploaded, 'html_url': 'https://example.invalid/release'}
                raise AssertionError(path)
            with patch.object(m, 'retired', return_value=False), patch.object(m, 'api', fake_api):
                self.assertEqual(m.publish('2.0.1-oneplus.1', 'a' * 40, folder), 'https://example.invalid/release')
            self.assertEqual(calls[-1][1], 'PATCH')

    def test_existing_published_assets_are_never_replaced(self):
        with tempfile.TemporaryDirectory() as folder:
            self.artifact(folder)
            release = {'draft': False, 'html_url': 'https://example.invalid/existing', 'assets': [
                {'name': 'CardFace-2.0.1-oneplus.1.apk', 'state': 'uploaded', 'size': 2000},
                {'name': 'CardFace-2.0.1-oneplus.1.apk.sha256', 'state': 'uploaded', 'size': 95}]}
            with patch.object(m, 'retired', return_value=False), patch.object(m, 'api', return_value=release) as api:
                m.publish('2.0.1-oneplus.1', 'a' * 40, folder)
                self.assertEqual(api.call_count, 1)


if __name__ == '__main__':
    unittest.main()
