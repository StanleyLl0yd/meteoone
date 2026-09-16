from __future__ import annotations

import re
import unittest
from pathlib import Path


WORKFLOW = Path(".github/workflows/signed-release-build.yml")
REQUIRED_SECRETS = (
    "ANDROID_KEYSTORE_BASE64",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
    "ANDROID_UPLOAD_CERT_SHA256",
)


class SignedReleaseWorkflowPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text(encoding="utf-8")

    def test_is_manual_only(self) -> None:
        self.assertIn("workflow_dispatch:", self.text)
        self.assertIsNone(re.search(r"(?m)^\s{2}(?:push|pull_request|pull_request_target):", self.text))

    def test_does_not_publish_or_mutate_repository(self) -> None:
        forbidden = (
            "contents: write",
            "gh release",
            "git push",
            "refs/tags/",
            "secrets: inherit",
        )
        for value in forbidden:
            with self.subTest(value=value):
                self.assertNotIn(value, self.text)

    def test_uses_repository_secrets_without_release_environment(self) -> None:
        self.assertNotIn("environment: release", self.text)
        for secret in REQUIRED_SECRETS:
            with self.subTest(secret=secret):
                self.assertIn(f"secrets.{secret}", self.text)

    def test_requires_release_signing_and_reconfirms_main(self) -> None:
        self.assertIn('REQUIRE_RELEASE_SIGNING: "true"', self.text)
        self.assertGreaterEqual(
            self.text.count('test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)"'),
            2,
        )

    def test_records_public_build_provenance(self) -> None:
        self.assertIn('provenance_name="meteoone-$VERSION_NAME-build.txt"', self.text)
        self.assertIn('echo "source_sha=$GITHUB_SHA"', self.text)
        self.assertIn('echo "workflow_run=$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"', self.text)
        self.assertIn('echo "upload_certificate_sha256=$expected"', self.text)
        self.assertIn('"release/$provenance_name"', self.text)

    def test_exports_only_store_aab_not_upload_key_apk(self) -> None:
        self.assertIn('source_apk="app/build/outputs/apk/release/app-release.apk"', self.text)
        self.assertNotIn('apk_name="meteoone-$VERSION_NAME.apk"', self.text)
        self.assertNotIn("steps.package.outputs.apk_path", self.text)
        self.assertIn("Attest RuStore upload AAB", self.text)
        self.assertIn("Upload signed AAB for manual RuStore publication", self.text)

    def test_cleans_temporary_keystore(self) -> None:
        self.assertIn("if: always()", self.text)
        self.assertIn('rm -f "$RUNNER_TEMP/meteoone-rustore-upload.jks"', self.text)


if __name__ == "__main__":
    unittest.main()
