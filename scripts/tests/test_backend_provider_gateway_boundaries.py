from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "backend" / "provider-gateway" / "build.gradle.kts"
MAIN = ROOT / "backend" / "provider-gateway" / "src" / "main" / "kotlin"


def kotlin_sources(root: Path) -> str:
    return "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(root.rglob("*.kt"))
    )


class BackendProviderGatewayBoundaryTest(unittest.TestCase):
    def test_provider_gateway_is_server_jvm_boundary(self) -> None:
        build = BUILD.read_text(encoding="utf-8")
        sources = kotlin_sources(MAIN)

        self.assertIn('alias(libs.plugins.kotlin.jvm)', build)
        self.assertIn('api(project(":core:model"))', build)
        self.assertIn('implementation(project(":core:network"))', build)

        for forbidden in (
            'android.',
            'androidx.',
            'io.ktor',
            ':core:location',
            ':core:database',
            ':forecast:',
            ':verification:',
            ':backend:contract',
            ':backend:gateway',
        ):
            self.assertNotIn(forbidden, build + "\n" + sources)

    def test_generic_gateway_has_no_environment_secret_lookup(self) -> None:
        sources = kotlin_sources(MAIN)
        self.assertNotIn('System.getenv', sources)
        self.assertNotIn('System.getProperty', sources)


if __name__ == "__main__":
    unittest.main()
