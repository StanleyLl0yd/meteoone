from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SETTINGS = ROOT / "settings.gradle.kts"
BUILD = ROOT / "backend" / "orchestration" / "build.gradle.kts"
MAIN = ROOT / "backend" / "orchestration" / "src" / "main" / "kotlin"


class BackendOrchestrationBoundaryTest(unittest.TestCase):
    def test_orchestration_stays_server_pure_and_room_independent(self) -> None:
        settings = SETTINGS.read_text(encoding="utf-8")
        build = BUILD.read_text(encoding="utf-8")
        sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted(MAIN.rglob("*.kt"))
        )

        self.assertIn('include(":backend:orchestration")', settings)
        self.assertIn('alias(libs.plugins.kotlin.jvm)', build)
        self.assertIn('implementation(project(":backend:gateway"))', build)
        self.assertIn('implementation(project(":backend:provider-adapters"))', build)
        self.assertIn('api(project(":forecast:domain"))', build)

        forbidden = (
            "com.android.",
            "android.",
            "androidx.",
            'project(":app")',
            'project(":core:database")',
            'project(":forecast:data")',
            'project(":forecast:repository")',
        )
        combined = build + "\n" + sources
        for token in forbidden:
            self.assertNotIn(token, combined)

        self.assertIn("ForecastGatewayCacheKey", sources)
        self.assertIn("SingleFlightForecastGatewayCache", sources)
        self.assertIn("ForecastSourceOrchestrator", sources)
        self.assertIn("ForecastOfficialRunPolicy", sources)


if __name__ == "__main__":
    unittest.main()
