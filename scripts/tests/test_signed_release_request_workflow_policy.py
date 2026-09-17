from __future__ import annotations

import unittest
from pathlib import Path


WORKFLOW = Path(".github/workflows/signed-release-request.yml")


class SignedReleaseRequestWorkflowPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text(encoding="utf-8")

    def test_is_issue_comment_only(self) -> None:
        self.assertIn("issue_comment:", self.text)
        self.assertIn("types: [created]", self.text)
        for forbidden in ("workflow_dispatch:", "push:", "pull_request:", "pull_request_target:"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, self.text)

    def test_is_owner_only_exact_generic_release_command(self) -> None:
        self.assertIn("!github.event.issue.pull_request", self.text)
        self.assertIn("github.event.comment.user.login == github.repository_owner", self.text)
        self.assertIn("github.event.comment.body == '/build-release'", self.text)
        self.assertNotIn("github.event.issue.number == 153", self.text)
        self.assertNotIn("/build-signed-alpha", self.text)

    def test_has_only_actions_write_job_permission(self) -> None:
        self.assertIn("permissions: {}", self.text)
        self.assertIn("actions: write", self.text)
        self.assertNotIn("contents: write", self.text)

    def test_has_no_signing_or_store_secrets(self) -> None:
        self.assertNotIn("secrets.", self.text)
        self.assertNotIn("ANDROID_KEYSTORE", self.text)
        self.assertNotIn("ANDROID_KEY_PASSWORD", self.text)
        self.assertNotIn("ANDROID_UPLOAD_CERT", self.text)
        self.assertNotIn("RUSTORE", self.text.upper())

    def test_dispatches_only_existing_release_workflow_on_main(self) -> None:
        self.assertIn("gh workflow run signed-release-build.yml", self.text)
        self.assertIn('--repo "$GITHUB_REPOSITORY"', self.text)
        self.assertIn("--ref main", self.text)
        self.assertNotIn("git push", self.text)
        self.assertNotIn("gh release", self.text)
        self.assertNotIn("refs/tags/", self.text)


if __name__ == "__main__":
    unittest.main()
