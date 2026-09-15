from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
APP_BUILD = ROOT / "app" / "build.gradle.kts"
CORE_DATABASE_BUILD = ROOT / "core" / "database" / "build.gradle.kts"
FORECAST_DATA_BUILD = ROOT / "forecast" / "data" / "build.gradle.kts"


class ForecastModuleBoundaryTest(unittest.TestCase):
    def test_app_routes_forecast_access_through_data_module(self) -> None:
        app_build = APP_BUILD.read_text(encoding="utf-8")

        self.assertIn('implementation(project(":forecast:data"))', app_build)
        self.assertNotIn('project(":forecast:domain")', app_build)

    def test_data_module_hides_domain_behind_public_facade(self) -> None:
        data_build = FORECAST_DATA_BUILD.read_text(encoding="utf-8")

        self.assertIn('implementation(project(":forecast:domain"))', data_build)
        self.assertNotIn('api(project(":forecast:domain"))', data_build)

    def test_database_module_stays_below_forecast_execution(self) -> None:
        database_build = CORE_DATABASE_BUILD.read_text(encoding="utf-8")

        self.assertIn('api(project(":core:model"))', database_build)
        for forbidden in (
            ':core:location',
            ':core:network',
            ':forecast:data',
            ':forecast:domain',
        ):
            self.assertNotIn(f'project("{forbidden}")', database_build)


if __name__ == "__main__":
    unittest.main()
