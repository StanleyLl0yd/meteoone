from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
APP_BUILD = ROOT / "app" / "build.gradle.kts"
CORE_DATABASE_BUILD = ROOT / "core" / "database" / "build.gradle.kts"
FORECAST_DATA_BUILD = ROOT / "forecast" / "data" / "build.gradle.kts"
FORECAST_REPOSITORY_BUILD = ROOT / "forecast" / "repository" / "build.gradle.kts"
VERIFICATION_DOMAIN_BUILD = ROOT / "verification" / "domain" / "build.gradle.kts"
VERIFICATION_DATA_BUILD = ROOT / "verification" / "data" / "build.gradle.kts"
VERIFICATION_DOMAIN_MAIN = (
    ROOT / "verification" / "domain" / "src" / "main" / "kotlin"
)
VERIFICATION_DATA_MAIN = (
    ROOT / "verification" / "data" / "src" / "main" / "kotlin"
)
FORECAST_REPOSITORY_FACADE = (
    ROOT
    / "forecast"
    / "repository"
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "sl"
    / "meteoone"
    / "forecast"
    / "repository"
    / "ForecastRepository.kt"
)


def kotlin_sources(root: Path) -> str:
    return "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(root.rglob("*.kt"))
    )


class VerificationModuleBoundaryTest(unittest.TestCase):
    def test_verification_domain_stays_pure(self) -> None:
        build = VERIFICATION_DOMAIN_BUILD.read_text(encoding="utf-8")
        sources = kotlin_sources(VERIFICATION_DOMAIN_MAIN)

        self.assertIn('api(project(":core:model"))', build)
        for forbidden in (
            ':core:network',
            ':core:database',
            ':forecast:',
            ':verification:data',
            'androidx.',
            'android.',
            'okhttp',
            'retrofit',
        ):
            self.assertNotIn(forbidden, build + "\n" + sources)

    def test_verification_data_owns_transport_not_persistence_or_forecast(self) -> None:
        build = VERIFICATION_DATA_BUILD.read_text(encoding="utf-8")
        sources = kotlin_sources(VERIFICATION_DATA_MAIN)

        self.assertIn('api(project(":verification:domain"))', build)
        self.assertIn('implementation(project(":core:network"))', build)
        for forbidden in (
            ':core:database',
            ':forecast:',
            'androidx.room',
            'com.sl.meteoone.core.database',
            'com.sl.meteoone.forecast.',
        ):
            self.assertNotIn(forbidden, build + "\n" + sources)

    def test_database_persists_verification_domain_without_execution_dependency(self) -> None:
        build = CORE_DATABASE_BUILD.read_text(encoding="utf-8")

        self.assertIn('api(project(":verification:domain"))', build)
        self.assertNotIn('project(":verification:data")', build)
        for forbidden in (
            ':core:network',
            ':forecast:data',
            ':forecast:domain',
            ':forecast:repository',
        ):
            self.assertNotIn(f'project("{forbidden}")', build)

    def test_forecast_data_consumes_only_verification_domain_contract(self) -> None:
        build = FORECAST_DATA_BUILD.read_text(encoding="utf-8")

        self.assertIn('api(project(":verification:domain"))', build)
        self.assertNotIn('project(":verification:data")', build)
        self.assertNotIn('project(":core:database")', build)

    def test_app_and_repository_facade_do_not_expose_verification_implementation(self) -> None:
        app_build = APP_BUILD.read_text(encoding="utf-8")
        repository_build = FORECAST_REPOSITORY_BUILD.read_text(encoding="utf-8")
        repository_facade = FORECAST_REPOSITORY_FACADE.read_text(encoding="utf-8")

        for forbidden in (':verification:domain', ':verification:data'):
            self.assertNotIn(f'project("{forbidden}")', app_build)
            self.assertNotIn(f'project("{forbidden}")', repository_build)
        self.assertNotIn("com.sl.meteoone.verification", repository_facade)


if __name__ == "__main__":
    unittest.main()
