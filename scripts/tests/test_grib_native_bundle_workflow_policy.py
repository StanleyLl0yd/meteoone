from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "grib-native-bundle.yml"


class GribNativeBundleWorkflowPolicyTest(unittest.TestCase):
    def test_publication_discards_checkout_drift_before_generated_branch_switch(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        reset = workflow.index('git reset --hard "${GITHUB_SHA}"')
        clean = workflow.index("git clean -ffd", reset)
        status = workflow.index('test -z "$(git status --porcelain)"', clean)
        switch = workflow.index("git switch", status)

        self.assertLess(reset, clean)
        self.assertLess(clean, status)
        self.assertLess(status, switch)


if __name__ == "__main__":
    unittest.main()
