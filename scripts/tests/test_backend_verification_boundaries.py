from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SETTINGS = ROOT / "settings.gradle.kts"
BUILD = ROOT / "backend" / "verification" / "build.gradle.kts"
MAIN = ROOT / "backend" / "verification" / "src" / "main" / "kotlin"


class BackendVerificationBoundaryTest(unittest.TestCase):
    def test_server_verification_store_is_pure_and_room_independent(self) -> None:
        settings = SETTINGS.read_text(encoding="utf-8")
        build = BUILD.read_text(encoding="utf-8")
        sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted(MAIN.rglob("*.kt"))
        )

        self.assertIn('include(":backend:verification")', settings)
        self.assertIn('alias(libs.plugins.kotlin.jvm)', build)
        self.assertIn('api(project(":verification:domain"))', build)
        self.assertIn('implementation(project(":backend:provider-gateway"))', build)
        self.assertIn('implementation(project(":forecast:openmeteo"))', build)
        self.assertIn('implementation(project(":verification:data"))', build)
        self.assertIn("ForecastVerificationRunEvidence", sources)
        self.assertIn("MAX_RETENTION", sources)

        combined = build + "\n" + sources
        for token in (
            "android.",
            "androidx.",
            "androidx.room",
            'project(":core:database")',
            'project(":forecast:repository")',
            'project(":forecast:data")',
        ):
            self.assertNotIn(token, combined)


if __name__ == "__main__":
    unittest.main()
