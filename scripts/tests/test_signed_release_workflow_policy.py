from __future__ import annotations

import re
import unittest
from pathlib import Path


WORKFLOW = Path(".github/workflows/signed-release-build.yml")
LEGACY_PARALLEL_WORKFLOWS = (
    Path(".github/workflows/signed-test-apk.yml"),
    Path(".github/workflows/signed-test-apk-request.yml"),
)
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
            "RUSTORE_API",
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

    def test_builds_apk_and_aab_in_one_release_run(self) -> None:
        self.assertIn(":app:assembleRelease", self.text)
        self.assertIn(":app:bundleRelease", self.text)
        self.assertIn('source_apk="app/build/outputs/apk/release/app-release.apk"', self.text)
        self.assertIn('source_aab="app/build/outputs/bundle/release/app-release.aab"', self.text)

    def test_exports_verified_apk_and_aab_together(self) -> None:
        self.assertIn('apk_name="meteoone-$VERSION_NAME.apk"', self.text)
        self.assertIn('aab_name="meteoone-$VERSION_NAME.aab"', self.text)
        self.assertIn('echo "apk_path=release/$apk_name"', self.text)
        self.assertIn('echo "aab_path=release/$aab_name"', self.text)
        self.assertIn("steps.package.outputs.apk_path", self.text)
        self.assertIn("steps.package.outputs.aab_path", self.text)
        self.assertIn("Upload signed APK and AAB release bundle", self.text)
        self.assertIn("retention-days: 30", self.text)

    def test_checksums_cover_both_binary_artifacts(self) -> None:
        self.assertIn('"release/$apk_name"', self.text)
        self.assertIn('"release/$aab_name"', self.text)
        self.assertIn("sha256sum --check SHA256SUMS", self.text)

    def test_records_artifact_roles_and_public_build_provenance(self) -> None:
        self.assertIn('provenance_name="meteoone-$VERSION_NAME-build.txt"', self.text)
        self.assertIn('echo "source_sha=$GITHUB_SHA"', self.text)
        self.assertIn(
            'echo "workflow_run=$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"',
            self.text,
        )
        self.assertIn('echo "signing_certificate_sha256=$expected"', self.text)
        self.assertIn('echo "upload_certificate_sha256=$expected"', self.text)
        self.assertIn('echo "apk_role=manual-device-smoke-test"', self.text)
        self.assertIn('echo "aab_role=manual-rustore-upload-after-apk-pass"', self.text)
        self.assertIn('echo "not_rustore_delivered_apk=true"', self.text)
        self.assertIn('"release/$provenance_name"', self.text)

    def test_attests_both_binary_artifacts(self) -> None:
        self.assertIn("Attest manual-test APK", self.text)
        self.assertIn("Attest RuStore upload AAB", self.text)
        self.assertIn("subject-path: ${{ steps.package.outputs.apk_path }}", self.text)
        self.assertIn("subject-path: ${{ steps.package.outputs.aab_path }}", self.text)

    def test_has_single_release_chain(self) -> None:
        for path in LEGACY_PARALLEL_WORKFLOWS:
            with self.subTest(path=path):
                self.assertFalse(path.exists(), f"parallel release workflow must not exist: {path}")

    def test_cleans_temporary_keystore(self) -> None:
        self.assertIn("if: always()", self.text)
        self.assertIn('rm -f "$RUNNER_TEMP/meteoone-release.jks"', self.text)


if __name__ == "__main__":
    unittest.main()
