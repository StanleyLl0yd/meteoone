from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SETTINGS = ROOT / "settings.gradle.kts"
BUILD = ROOT / "forecast" / "official" / "build.gradle.kts"
MAIN = ROOT / "forecast" / "official" / "src" / "main" / "kotlin"


def kotlin_sources(root: Path) -> str:
    return "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(root.rglob("*.kt"))
    )


class ForecastOfficialBoundaryTest(unittest.TestCase):
    def test_official_contract_is_pure_jvm_and_android_independent(self) -> None:
        settings = SETTINGS.read_text(encoding="utf-8")
        build = BUILD.read_text(encoding="utf-8")
        sources = kotlin_sources(MAIN)

        self.assertIn('include(":forecast:official")', settings)
        self.assertIn('alias(libs.plugins.kotlin.jvm)', build)
        self.assertIn('api(project(":core:model"))', build)
        self.assertIn('implementation(libs.kotlinx.serialization.json)', build)
        self.assertIn('implementation(libs.commons.compress)', build)

        for forbidden in (
            'android.',
            'androidx.',
            ':forecast:data',
            ':forecast:repository',
            ':core:network',
            ':core:location',
            ':core:database',
            ':backend:',
            ':verification:',
        ):
            self.assertNotIn(forbidden, build + "\n" + sources)


if __name__ == "__main__":
    unittest.main()
