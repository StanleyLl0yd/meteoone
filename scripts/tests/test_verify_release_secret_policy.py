import unittest

from scripts.verify_release_secret_policy import (
    REQUIRED_IGNORE_PATTERNS,
    is_forbidden_tracked_path,
    missing_required_patterns,
    verify_policy,
)


class ReleaseSecretPolicyTest(unittest.TestCase):
    def setUp(self):
        self.gitignore = "\n".join(sorted(REQUIRED_IGNORE_PATTERNS)) + "\n"

    def test_accepts_required_ignore_policy_and_normal_sources(self):
        verify_policy(
            self.gitignore,
            [
                "app/build.gradle.kts",
                "docs/release/SIGNING.md",
                "scripts/verify_release_secret_policy.py",
            ],
        )

    def test_reports_missing_pepk_export_ignore(self):
        text = self.gitignore.replace("*pepk*.zip\n", "")

        self.assertEqual({"*pepk*.zip"}, missing_required_patterns(text))
        with self.assertRaises(ValueError):
            verify_policy(text, [])

    def test_rejects_tracked_keystore_and_pepk_export(self):
        with self.assertRaises(ValueError):
            verify_policy(
                self.gitignore,
                ["keys/release.JKS", "private/rustore-pepk-out.ZIP"],
            )

    def test_rejects_nested_secrets_directory(self):
        self.assertTrue(is_forbidden_tracked_path("tools/.secrets/release-password.txt"))

    def test_rejects_tracked_pem_even_when_certificate_might_be_public(self):
        self.assertTrue(is_forbidden_tracked_path("release/uploadcert.pem"))

    def test_does_not_reject_fingerprint_documentation(self):
        self.assertFalse(is_forbidden_tracked_path("docs/release/CERTIFICATE_FINGERPRINTS.md"))


if __name__ == "__main__":
    unittest.main()
