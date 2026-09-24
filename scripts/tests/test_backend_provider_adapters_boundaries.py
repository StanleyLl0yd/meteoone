from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SETTINGS = ROOT / "settings.gradle.kts"
BUILD = ROOT / "backend" / "provider-adapters" / "build.gradle.kts"
MAIN = ROOT / "backend" / "provider-adapters" / "src" / "main" / "kotlin"


def kotlin_sources(root: Path) -> str:
    return "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(root.rglob("*.kt"))
    )


class BackendProviderAdaptersBoundaryTest(unittest.TestCase):
    def test_provider_adapters_are_pure_server_jvm(self) -> None:
        settings = SETTINGS.read_text(encoding="utf-8")
        build = BUILD.read_text(encoding="utf-8")
        sources = kotlin_sources(MAIN)

        self.assertIn('include(":backend:provider-adapters")', settings)
        self.assertIn('alias(libs.plugins.kotlin.jvm)', build)
        self.assertIn('api(project(":core:model"))', build)
        self.assertIn('implementation(project(":backend:provider-gateway"))', build)
        self.assertIn('implementation(project(":forecast:openmeteo"))', build)

        for forbidden in (
            'android.',
            'androidx.',
            ':forecast:data',
            ':forecast:repository',
            ':core:location',
            ':core:database',
            ':verification:',
            'System.getenv',
            'System.getProperty',
        ):
            self.assertNotIn(forbidden, build + "\n" + sources)


if __name__ == "__main__":
    unittest.main()
