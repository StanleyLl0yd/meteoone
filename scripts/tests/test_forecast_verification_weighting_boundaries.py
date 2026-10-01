from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SETTINGS = ROOT / "settings.gradle.kts"
BUILD = ROOT / "forecast" / "verification-weighting" / "build.gradle.kts"
MAIN = ROOT / "forecast" / "verification-weighting" / "src" / "main" / "kotlin"


class ForecastVerificationWeightingBoundaryTest(unittest.TestCase):
    def test_weighting_bridge_is_pure_jvm_and_reuses_verification_domain(self) -> None:
        settings = SETTINGS.read_text(encoding="utf-8")
        build = BUILD.read_text(encoding="utf-8")
        sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted(MAIN.rglob("*.kt"))
        )

        self.assertIn('include(":forecast:verification-weighting")', settings)
        self.assertIn('alias(libs.plugins.kotlin.jvm)', build)
        self.assertIn('api(project(":forecast:domain"))', build)
        self.assertIn('api(project(":verification:domain"))', build)

        combined = build + "\n" + sources
        for token in (
            "android.",
            "androidx.",
            'project(":forecast:data")',
            'project(":forecast:repository")',
            'project(":core:database")',
            'project(":verification:data")',
        ):
            self.assertNotIn(token, combined)

        self.assertIn("VerificationWeightPolicy", sources)
        self.assertIn("ForecastModelWeightProvider", sources)
        self.assertIn("ForecastModelWeightDecision.EqualFallback", sources)


if __name__ == "__main__":
    unittest.main()
