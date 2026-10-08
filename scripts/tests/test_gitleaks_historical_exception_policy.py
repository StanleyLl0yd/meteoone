"""Guard historical Gitleaks exceptions against broad suppression."""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
IGNORE_FILE = ROOT / ".gitleaksignore"
EXACT_HISTORICAL_FINGERPRINT = (
    "21b79f2ddc741d77140a7a82fcf51d54b2f1957f:"
    "forecast/repository/src/test/kotlin/com/sl/meteoone/forecast/repository/"
    "BackendForecastRefreshSourceTest.kt:generic-api-key:48"
)
EXACT_FINGERPRINT_PATTERN = re.compile(
    r"^[0-9a-f]{40}:[^:\\r\\n]+:[a-z][a-z0-9-]*:[1-9][0-9]*$"
)


class GitleaksHistoricalExceptionPolicyTest(unittest.TestCase):
    def test_only_specific_commit_scoped_fingerprints_are_ignored(self):
        entries = [
            line.strip()
            for line in IGNORE_FILE.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        ]
        self.assertEqual(entries, [EXACT_HISTORICAL_FINGERPRINT])
        for entry in entries:
            self.assertRegex(entry, EXACT_FINGERPRINT_PATTERN)


if __name__ == "__main__":
    unittest.main()
