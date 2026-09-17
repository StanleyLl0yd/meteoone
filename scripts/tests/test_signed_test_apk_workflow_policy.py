from __future__ import annotations

import re
import unittest
from pathlib import Path


BUILD_WORKFLOW = Path(".github/workflows/signed-test-apk.yml")
REQUEST_WORKFLOW = Path(".github/workflows/signed-test-apk-request.yml")
RELEASE_SOURCE_SHA = "ae27269aa753ebcd812922b5023d5f6854810df9"
REQUIRED_SECRETS = (
    "ANDROID_KEYSTORE_BASE64",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
    "ANDROID_UPLOAD_CERT_SHA256",
)


class SignedTestApkWorkflowPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.build = BUILD_WORKFLOW.read_text(encoding="utf-8")
        cls.request = REQUEST_WORKFLOW.read_text(encoding="utf-8")

    def test_build_is_manual_only_and_read_only(self) -> None:
        self.assertIn("workflow_dispatch:", self.build)
        self.assertIsNone(
            re.search(r"(?m)^\s{2}(?:push|pull_request|pull_request_target|issue_comment):", self.build)
        )
        self.assertIn("permissions: {}", self.build)
        self.assertIn("contents: read", self.build)
        for forbidden in ("contents: write", "actions: write", "gh release", "git push", "refs/tags/"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, self.build)

    def test_build_is_pinned_to_frozen_alpha_source(self) -> None:
        self.assertIn(f"RELEASE_SOURCE_SHA: {RELEASE_SOURCE_SHA}", self.build)
        self.assertIn("ref: ${{ env.RELEASE_SOURCE_SHA }}", self.build)
        self.assertIn('test "$(git rev-parse HEAD)" = "$RELEASE_SOURCE_SHA"', self.build)
        self.assertIn("--expected-version-name \"$VERSION_NAME\"", self.build)
        self.assertIn("--expected-version-code \"$VERSION_CODE\"", self.build)

    def test_build_requires_all_signing_secrets_and_release_signing(self) -> None:
        for secret in REQUIRED_SECRETS:
            with self.subTest(secret=secret):
                self.assertIn(f"secrets.{secret}", self.build)
        self.assertIn('REQUIRE_RELEASE_SIGNING: "true"', self.build)
        self.assertIn(":app:assembleRelease", self.build)
        self.assertNotIn(":app:bundleRelease", self.build)

    def test_build_verifies_apk_identity_signature_and_native_payload(self) -> None:
        for value in (
            'test "$package_name" = "com.sl.meteoone"',
            'test "$package_version_code" = "$VERSION_CODE"',
            'test "$package_version_name" = "$VERSION_NAME"',
            "Verified using v2 scheme (APK Signature Scheme v2): true",
            "Verified using v3 scheme (APK Signature Scheme v3): true",
            "Number of signers: 1",
            "libaec.so",
            "libeccodes.so",
            "libmeteoone_grib_jni.so",
            "libsz.so",
        ):
            with self.subTest(value=value):
                self.assertIn(value, self.build)

    def test_build_exports_only_test_apk_with_explicit_provenance(self) -> None:
        self.assertIn('apk_name="meteoone-$VERSION_NAME-device-test.apk"', self.build)
        self.assertIn("purpose=device-functional-test", self.build)
        self.assertIn("not_rustore_delivered_apk=true", self.build)
        self.assertIn("release_source_sha=$RELEASE_SOURCE_SHA", self.build)
        self.assertIn("Upload signed APK for direct device testing", self.build)
        self.assertIn("retention-days: 7", self.build)
        self.assertNotIn("RUSTORE_API", self.build)
        self.assertNotIn("publish", self.build.lower())

    def test_build_cleans_temporary_keystore(self) -> None:
        self.assertIn("if: always()", self.build)
        self.assertIn('rm -f "$RUNNER_TEMP/meteoone-device-test.jks"', self.build)

    def test_request_is_owner_only_release_issue_bridge(self) -> None:
        self.assertIn("issue_comment:", self.request)
        self.assertIn("types: [created]", self.request)
        self.assertIn("github.event.issue.number == 153", self.request)
        self.assertIn("github.event.comment.user.login == github.repository_owner", self.request)
        self.assertIn("github.event.comment.body == '/build-signed-test-apk'", self.request)
        self.assertIn("actions: write", self.request)
        self.assertNotIn("secrets.", self.request)
        self.assertIn("gh workflow run signed-test-apk.yml", self.request)
        self.assertIn("--ref main", self.request)


if __name__ == "__main__":
    unittest.main()
