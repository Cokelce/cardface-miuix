import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('notes_module', Path(__file__).with_name('release_notes.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class NotesTests(unittest.TestCase):
    def test_only_two_user_facing_sections(self):
        text = m.notes('2.0.1-oneplus.1')
        self.assertEqual([line for line in text.splitlines() if line.startswith('## ')], ['## 上游更新', '## OnePlus 和 OPPO 钱包适配'])
        for extra in ['SHA256', 'Tests=', '源码提交', 'actions/runs', '真机验收', '签名检查', '唯一目标']:
            self.assertNotIn(extra, text)
        self.assertIn('上传损坏', text)
        self.assertIn('卡面替换', text)

    def test_unknown_version_does_not_repeat_old_notes(self):
        with self.assertRaises(ValueError):
            m.notes('99.0.0-oneplus.1')

    def test_known_baseline_notes(self):
        text = m.notes('2.0.0-oneplus.5')
        self.assertIn('Hibank', text)
        self.assertIn('四入口', text)

if __name__ == '__main__':
    unittest.main()
