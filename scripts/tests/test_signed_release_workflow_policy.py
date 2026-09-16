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

    def test_uses_protected_release_environment_and_explicit_secrets(self) -> None:
        self.assertIn("environment: release", self.text)
        for secret in REQUIRED_SECRETS:
            with self.subTest(secret=secret):
                self.assertIn(f"secrets.{secret}", self.text)

    def test_cleans_temporary_keystore(self) -> None:
        self.assertIn("if: always()", self.text)
        self.assertIn('rm -f "$RUNNER_TEMP/meteoone-rustore-upload.jks"', self.text)


if __name__ == "__main__":
    unittest.main()
