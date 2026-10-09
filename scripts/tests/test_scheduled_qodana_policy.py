"""Regression policy for the optional scheduled Qodana linter."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "security.yml"
CONFIG = ROOT / "qodana.yaml"


class ScheduledQodanaPolicyTest(unittest.TestCase):
    def test_effective_linter_is_immutable_and_has_java_trust(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(
            "jetbrains/qodana-jvm-community@sha256:"
            "3db2bdb1d846ddeca9f600a6737bc82b3993067e07e572ba7bf791d9fe41a9bf",
            workflow,
        )
        self.assertIn("cp -aL", workflow)
        self.assertIn('test -r "$RUNNER_TEMP/qodana-jdk17/lib/security/cacerts"', workflow)
        self.assertIn("--within-docker true", workflow)
        self.assertIn("--env JAVA_HOME=/opt/meteoone-jdk17", workflow)
        self.assertNotIn("github.head_ref == 'm5/qodana-stabilization-validation'", workflow)
        self.assertIn("github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'", workflow)
        self.assertIn("failThreshold: 0", CONFIG.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
