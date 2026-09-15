from __future__ import annotations

import unittest
from pathlib import Path

from scripts.verify_ci_supply_chain import verify_document


ACTION_SHA = "a" * 40
DIGEST = "sha256:" + "a" * 64


class CiSupplyChainVerifierTest(unittest.TestCase):
    def test_docker_action_requires_digest(self) -> None:
        path = Path(".github/actions/example/action.yml")
        errors = verify_document(
            path,
            "runs:\n  using: composite\n  steps:\n    - uses: docker://alpine:3.22\n",
            is_workflow=False,
        )
        self.assertTrue(any("Docker action image must be pinned" in error for error in errors))

        errors = verify_document(
            path,
            f"runs:\n  using: composite\n  steps:\n    - uses: docker://alpine@{DIGEST}\n",
            is_workflow=False,
        )
        self.assertEqual(errors, [])

    def test_checkout_requires_persist_credentials_false_in_its_own_step(self) -> None:
        path = Path(".github/workflows/example.yml")
        document = (
            "jobs:\n"
            "  verify:\n"
            "    steps:\n"
            f"      - uses: actions/checkout@{ACTION_SHA}\n"
            "      - name: separator\n"
            "        run: echo separator\n"
            f"      - uses: actions/checkout@{ACTION_SHA}\n"
            "        with:\n"
            "          persist-credentials: false\n"
        )
        errors = verify_document(path, document, is_workflow=False)
        checkout_errors = [
            error
            for error in errors
            if "actions/checkout must set persist-credentials: false" in error
        ]
        self.assertEqual(
            checkout_errors,
            [f"{path}:4: actions/checkout must set persist-credentials: false"],
        )

    def test_setup_android_requires_static_nonlegacy_packages(self) -> None:
        path = Path(".github/workflows/example.yml")
        prefix = (
            "jobs:\n"
            "  verify:\n"
            "    steps:\n"
            f"      - uses: android-actions/setup-android@{ACTION_SHA}\n"
            "        with:\n"
        )

        missing = verify_document(
            path,
            prefix + "          cmdline-tools-version: 15859902\n",
            is_workflow=False,
        )
        self.assertTrue(any("must set explicit packages" in error for error in missing))

        legacy = verify_document(
            path,
            prefix + "          packages: tools platform-tools\n",
            is_workflow=False,
        )
        self.assertTrue(any("must not request legacy tools" in error for error in legacy))

        dynamic = verify_document(
            path,
            prefix + "          packages: ${{ matrix.android_packages }}\n",
            is_workflow=False,
        )
        self.assertTrue(any("packages must be static" in error for error in dynamic))

        accepted = verify_document(
            path,
            prefix + "          packages: platform-tools\n",
            is_workflow=False,
        )
        self.assertEqual(accepted, [])

    def test_dynamic_container_image_is_rejected_as_unverifiable(self) -> None:
        path = Path(".github/workflows/example.yml")
        errors = verify_document(
            path,
            "jobs:\n  scan:\n    container:\n      image: ${{ matrix.image }}\n",
            is_workflow=False,
        )
        self.assertTrue(any("dynamic container image cannot be verified" in error for error in errors))

    def test_static_container_digest_is_accepted(self) -> None:
        path = Path(".github/workflows/example.yml")
        errors = verify_document(
            path,
            f"jobs:\n  scan:\n    container:\n      image: example/scanner@{DIGEST}\n",
            is_workflow=False,
        )
        self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
