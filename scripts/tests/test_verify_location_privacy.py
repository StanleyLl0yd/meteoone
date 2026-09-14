from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.verify_location_privacy import verify_repository


LOCATION_MANIFEST = """<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">
    <uses-permission android:name=\"android.permission.ACCESS_COARSE_LOCATION\" />
</manifest>
"""
EMPTY_MANIFEST = """<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />
"""


class LocationPrivacyVerifierTest(unittest.TestCase):
    def _root(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        manifest = root / "core/location/src/main/AndroidManifest.xml"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(LOCATION_MANIFEST, encoding="utf-8")
        return temporary, root

    def test_accepts_other_source_set_manifest_without_location_permissions(self) -> None:
        temporary, root = self._root()
        with temporary:
            debug_manifest = root / "app/src/debug/AndroidManifest.xml"
            debug_manifest.parent.mkdir(parents=True)
            debug_manifest.write_text(EMPTY_MANIFEST, encoding="utf-8")
            verify_repository(root)

    def test_rejects_precise_permission_in_non_main_manifest(self) -> None:
        temporary, root = self._root()
        with temporary:
            debug_manifest = root / "app/src/debug/AndroidManifest.xml"
            debug_manifest.parent.mkdir(parents=True)
            debug_manifest.write_text(
                """<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">
    <uses-permission android:name=\"android.permission.ACCESS_FINE_LOCATION\" />
</manifest>
""",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "ACCESS_FINE_LOCATION"):
                verify_repository(root)

    def test_rejects_android_location_api_in_java_source(self) -> None:
        temporary, root = self._root()
        with temporary:
            source = root / "app/src/debug/java/example/Leak.java"
            source.parent.mkdir(parents=True)
            source.write_text(
                "class Leak { android.location.Location value; }\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "android.location APIs"):
                verify_repository(root)

    def test_allows_location_api_in_core_location_any_source_set(self) -> None:
        temporary, root = self._root()
        with temporary:
            source = root / "core/location/src/test/java/example/Allowed.java"
            source.parent.mkdir(parents=True)
            source.write_text(
                "class Allowed { android.location.Location value; }\n",
                encoding="utf-8",
            )
            verify_repository(root)


if __name__ == "__main__":
    unittest.main()
