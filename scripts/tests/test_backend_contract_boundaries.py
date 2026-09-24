from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_BUILD = ROOT / "backend" / "contract" / "build.gradle.kts"
CONTRACT_MAIN = ROOT / "backend" / "contract" / "src" / "main" / "kotlin"


def kotlin_sources(root: Path) -> str:
    return "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(root.rglob("*.kt"))
    )


class BackendContractBoundaryTest(unittest.TestCase):
    def test_contract_stays_transport_framework_independent(self) -> None:
        build = CONTRACT_BUILD.read_text(encoding="utf-8")
        sources = kotlin_sources(CONTRACT_MAIN)

        self.assertIn('alias(libs.plugins.kotlin.jvm)', build)
        self.assertIn('alias(libs.plugins.kotlin.serialization)', build)
        self.assertIn('api(project(":core:model"))', build)
        self.assertIn('api(libs.kotlinx.serialization.json)', build)

        for forbidden in (
            'io.ktor',
            'android.',
            'androidx.',
            'okhttp',
            'retrofit',
            ':core:network',
            ':core:database',
            ':forecast:',
            ':verification:',
            ':backend:gateway',
        ):
            self.assertNotIn(forbidden, build + "\n" + sources)


if __name__ == "__main__":
    unittest.main()
