import tempfile
import unittest
from pathlib import Path

from human_run_dataset_analyzer import discover_manifests, stats


class HumanRunDatasetAnalyzerTest(unittest.TestCase):
    def test_discover_manifests_is_recursive_and_deterministic(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "b").mkdir()
            (root / "a").mkdir()
            (root / "b" / "z-manifest.json").write_text("{}", encoding="utf-8")
            (root / "a" / "manifest.json").write_text("{}", encoding="utf-8")
            self.assertEqual(
                [
                    root / "a" / "manifest.json",
                    root / "b" / "z-manifest.json",
                ],
                discover_manifests(root),
            )

    def test_stats_are_descriptive_not_ranked(self):
        value = stats([1, 2, 3, 4])
        self.assertEqual(value["n"], 4)
        self.assertEqual(value["median"], 2.5)
        self.assertIn("p95", value)
        self.assertNotIn("rank", value)
        self.assertNotIn("score", value)


if __name__ == "__main__":
    unittest.main()
