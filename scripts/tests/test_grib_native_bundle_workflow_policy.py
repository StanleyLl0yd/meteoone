from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "grib-native-bundle.yml"


class GribNativeBundleWorkflowPolicyTest(unittest.TestCase):
    def test_publication_force_discards_checkout_drift_before_generated_branch_switch(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        clean = workflow.index("git clean -ffd")
        fetch = workflow.index("git fetch --no-tags origin", clean)
        forced_existing = workflow.index(
            "git switch --discard-changes -C publish-grib-native",
            fetch,
        )
        forced_orphan = workflow.index(
            "git switch --discard-changes --orphan publish-grib-native",
            forced_existing,
        )
        remove = workflow.index("git rm -rf --ignore-unmatch .", forced_orphan)

        self.assertLess(clean, fetch)
        self.assertLess(fetch, forced_existing)
        self.assertLess(forced_existing, forced_orphan)
        self.assertLess(forced_orphan, remove)
        self.assertNotIn('test -z "$(git status --porcelain)"', workflow)


if __name__ == "__main__":
    unittest.main()
