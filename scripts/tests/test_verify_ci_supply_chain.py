from __future__ import annotations

import unittest
from pathlib import Path

from scripts.verify_ci_supply_chain import verify_document


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
