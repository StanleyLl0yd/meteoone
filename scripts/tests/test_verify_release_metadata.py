import unittest

from scripts.verify_release_metadata import (
    ReleaseMetadata,
    parse_release_metadata,
    verify_release_metadata,
)


BUILD_FILE = """
android {
    namespace = "com.sl.meteoone"
    defaultConfig {
        applicationId = "com.sl.meteoone"
        versionCode = 1
        versionName = "0.4.0"
    }
}
"""


class ReleaseMetadataVerifierTest(unittest.TestCase):
    def test_parses_and_accepts_numeric_metadata(self):
        metadata = parse_release_metadata(BUILD_FILE)

        self.assertEqual(
            ReleaseMetadata(
                namespace="com.sl.meteoone",
                application_id="com.sl.meteoone",
                version_code=1,
                version_name="0.4.0",
            ),
            metadata,
        )
        verify_release_metadata(
            metadata,
            expected_version_name="0.4.0",
            expected_version_code=1,
        )

    def test_rejects_application_identity_drift(self):
        metadata = parse_release_metadata(
            BUILD_FILE.replace(
                'applicationId = "com.sl.meteoone"',
                'applicationId = "example.changed"',
            )
        )

        with self.assertRaises(ValueError):
            verify_release_metadata(metadata)

    def test_rejects_non_positive_version_code(self):
        metadata = ReleaseMetadata(
            namespace="com.sl.meteoone",
            application_id="com.sl.meteoone",
            version_code=0,
            version_name="0.4.0",
        )

        with self.assertRaises(ValueError):
            verify_release_metadata(metadata)

    def test_rejects_suffixes_for_new_releases(self):
        for invalid_name in ("0.4.0-alpha.1", "0.4.0-beta", "0.4.0-rc.1", "v0.4.0", "0.4"):
            with self.subTest(invalid_name=invalid_name):
                with self.assertRaisesRegex(ValueError, "numeric major.minor.patch"):
                    verify_release_metadata(ReleaseMetadata(
                        namespace="com.sl.meteoone",
                        application_id="com.sl.meteoone",
                        version_code=5,
                        version_name=invalid_name,
                    ))

    def test_rejects_malformed_version_name(self):
        metadata = ReleaseMetadata(
            namespace="com.sl.meteoone",
            application_id="com.sl.meteoone",
            version_code=1,
            version_name="alpha-one",
        )

        with self.assertRaises(ValueError):
            verify_release_metadata(metadata)

    def test_rejects_expected_release_mismatch(self):
        metadata = parse_release_metadata(BUILD_FILE)

        with self.assertRaises(ValueError):
            verify_release_metadata(
                metadata,
                expected_version_name="0.4.1",
            )

    def test_rejects_duplicate_version_fields(self):
        with self.assertRaises(ValueError):
            parse_release_metadata(BUILD_FILE + '\nversionCode = 2\n')


if __name__ == "__main__":
    unittest.main()
