import sys
from pathlib import Path as _Path
sys.path.insert(0, str(_Path(__file__).resolve().parent))
#!/usr/bin/env python3
import importlib.util
import unittest
from pathlib import Path

p = Path(__file__).with_name("sync_upstream.py")
spec = importlib.util.spec_from_file_location("sync_upstream", p)
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class PureVersionTests(unittest.TestCase):
    def test_date_and_collisions(self):
        self.assertEqual(m.next_dated_version('2.1.0', '261119', []), ('2.1.0-261119', 0))
        self.assertEqual(m.next_dated_version('2.1.0', '261119', ['v2.1.0-261119']), ('2.1.0-2611191', 1))
        self.assertEqual(m.next_dated_version('2.1.0', '261119', ['v2.1.0-261119', 'v2.1.0-2611192']), ('2.1.0-2611193', 3))
        self.assertEqual(m.next_dated_version('2.1.0', '261120', ['v2.1.0-2611199']), ('2.1.0-261120', 0))
    def test_base_version_parser(self):
        self.assertEqual(m.parse_base_version('versionName = "7.12.3"'), "7.12.3")
        with self.assertRaises(RuntimeError): m.parse_base_version('versionName = "dev"')

class FixedConfigTests(unittest.TestCase):
    def test_fixed_constants(self):
        self.assertEqual(m.UPSTREAM_REPO, "MiamBao/cardface-miuix")
        self.assertEqual(m.TARGET_REPO, "Cokelce/cardface-miuix")
        self.assertEqual(m.UPSTREAM_SHA_HINT, "c0833af5ff1392f84f431a9d0ae78892062aad6e")

class MergePolicyTests(unittest.TestCase):
    def test_only_automation_and_version_files_are_managed(self):
        self.assertTrue(m.managed_conflict(".github/workflows/new.yml"))
        self.assertTrue(m.managed_conflict("app/build.gradle.kts"))
        self.assertTrue(m.managed_conflict("README.md"))
        self.assertFalse(m.managed_conflict("app/src/main/kotlin/com/dsh/miuix/cardface/xposed/HookEngine.kt"))
    def test_codegen_output(self):
        import tempfile, os
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "outputs"
            with patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}):
                m.emit(changed="true", branch="automation/upstream-test")
            self.assertIn("changed=true\n", output.read_text())

if __name__ == "__main__": unittest.main(verbosity=2)
