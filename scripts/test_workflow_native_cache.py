"""Keep non-relocatable native build caches scoped to their checkout name."""
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class NativeWorkflowCacheTest(unittest.TestCase):
    def test_sherpa_cache_does_not_cross_repository_renames(self):
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        block = re.search(
            r'(?ms)^      - name: Cache shared speech runtime\n(.*?)(?=^      - name:|\Z)',
            workflow,
        )
        self.assertIsNotNone(block, 'Shared speech runtime cache step is required')
        key = re.search(r'(?m)^          key: (.+)$', block.group(1))
        self.assertIsNotNone(key)
        self.assertIn('${{ github.repository }}', key.group(1))
        self.assertIn('${{ runner.os }}', key.group(1))
        self.assertIn("hashFiles('scripts/build_sherpa.py'", key.group(1))
        # CMake embeds absolute source/build directories. A broad fallback can
        # restore the old checkout's cache even when the primary key is scoped.
        self.assertNotIn('restore-keys:', block.group(1))
