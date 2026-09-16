import hashlib
import tempfile
import unittest
from pathlib import Path

from scripts.write_sha256_manifest import build_manifest, write_manifest


class Sha256ManifestWriterTest(unittest.TestCase):
    def test_writes_sorted_deterministic_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            second = root / "meteoone.apk"
            first = root / "meteoone.aab"
            first.write_bytes(b"aab-bytes")
            second.write_bytes(b"apk-bytes")

            manifest = build_manifest([second, first])

            expected = (
                f"{hashlib.sha256(b'aab-bytes').hexdigest()}  meteoone.aab\n"
                f"{hashlib.sha256(b'apk-bytes').hexdigest()}  meteoone.apk\n"
            )
            self.assertEqual(expected, manifest)

    def test_rejects_missing_artifacts(self):
        with self.assertRaises(ValueError):
            build_manifest([])

    def test_rejects_non_file_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError):
                build_manifest([Path(temporary)])

    def test_rejects_duplicate_output_names(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            left = root / "left"
            right = root / "right"
            left.mkdir()
            right.mkdir()
            (left / "artifact.aab").write_bytes(b"left")
            (right / "artifact.aab").write_bytes(b"right")

            with self.assertRaises(ValueError):
                build_manifest([left / "artifact.aab", right / "artifact.aab"])

    def test_rejects_symlink_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "target.aab"
            target.write_bytes(b"payload")
            link = root / "link.aab"
            try:
                link.symlink_to(target)
            except (NotImplementedError, OSError):
                self.skipTest("symlinks unavailable")

            with self.assertRaises(ValueError):
                build_manifest([link])

    def test_refuses_to_overwrite_release_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            artifact = Path(temporary) / "meteoone.aab"
            artifact.write_bytes(b"signed-release")

            with self.assertRaises(ValueError):
                write_manifest([artifact], artifact)

            self.assertEqual(b"signed-release", artifact.read_bytes())


if __name__ == "__main__":
    unittest.main()
