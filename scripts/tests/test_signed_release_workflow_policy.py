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
STORE_POLICY_DOCS = (
    Path("ROADMAP.md"),
    Path("docs/release/SIGNING.md"),
    Path("docs/release/GITHUB_SIGNED_BUILD.md"),
    Path("docs/release/RUSTORE_ALPHA.md"),
)
STORE_ACCEPTANCE_SENTENCE = (
    "Before any RuStore AAB upload, the APK from that same GitHub Release must pass "
    "manual device acceptance testing; if the APK fails, do not upload its AAB."
)


class SignedReleaseWorkflowPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text(encoding="utf-8")

    def test_is_manual_only(self) -> None:
        self.assertIn("workflow_dispatch:", self.text)
        self.assertIsNone(re.search(r"(?m)^\s{2}(?:push|pull_request|pull_request_target):", self.text))

    def test_publishes_only_to_github_release(self) -> None:
        self.assertIn("contents: write", self.text)
        self.assertIn("gh release create", self.text)
        self.assertNotIn("actions/upload-artifact", self.text)
        self.assertNotIn("actions/attest", self.text)
        self.assertNotIn("RUSTORE_API", self.text)
        self.assertNotIn("git push", self.text)

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
        self.assertIn('--target "$GITHUB_SHA"', self.text)

    def test_release_assets_are_only_apk_and_aab(self) -> None:
        self.assertIn('apk_name="meteoone-$VERSION_NAME.apk"', self.text)
        self.assertIn('aab_name="meteoone-$VERSION_NAME.aab"', self.text)
        self.assertIn('echo "apk_path=release/$apk_name"', self.text)
        self.assertIn('echo "aab_path=release/$aab_name"', self.text)
        self.assertIn('"$APK_PATH"', self.text)
        self.assertIn('"$AAB_PATH"', self.text)
        for forbidden in (
            "SHA256SUMS",
            "provenance_name=",
            "mapping_path=",
            "checksums_path=",
            "upload-certificate",
            "uploadcert.pem",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, self.text)

    def test_manual_apk_testing_is_not_a_github_release_creation_gate(self) -> None:
        for forbidden in (
            "manual-device-smoke-test",
            "manual-test APK",
            "after-apk-pass",
            "APK PASS",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, self.text)

    def test_rustore_handoff_requires_same_release_apk_acceptance(self) -> None:
        for path in STORE_POLICY_DOCS:
            with self.subTest(path=path):
                self.assertIn(STORE_ACCEPTANCE_SENTENCE, path.read_text(encoding="utf-8"))

    def test_prerelease_versions_are_marked_prerelease(self) -> None:
        self.assertIn('if [[ "$VERSION_NAME" == *-* ]]; then', self.text)
        self.assertIn("args+=(--prerelease)", self.text)

    def test_rejects_existing_release_identity(self) -> None:
        self.assertIn('tag="v$VERSION_NAME"', self.text)
        self.assertIn('gh release view "$tag"', self.text)
        self.assertIn('git ls-remote --exit-code --tags origin "refs/tags/$tag"', self.text)

    def test_has_single_release_chain(self) -> None:
        for path in LEGACY_PARALLEL_WORKFLOWS:
            with self.subTest(path=path):
                self.assertFalse(path.exists(), f"parallel release workflow must not exist: {path}")

    def test_cleans_temporary_keystore(self) -> None:
        self.assertIn("if: always()", self.text)
        self.assertIn('rm -f "$RUNNER_TEMP/meteoone-release.jks"', self.text)


if __name__ == "__main__":
    unittest.main()
