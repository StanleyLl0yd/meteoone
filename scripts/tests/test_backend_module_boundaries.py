from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SETTINGS = ROOT / "settings.gradle.kts"
APP_BUILD = ROOT / "app" / "build.gradle.kts"
BACKEND_BUILD = ROOT / "backend" / "gateway" / "build.gradle.kts"
BACKEND_MAIN = ROOT / "backend" / "gateway" / "src" / "main" / "kotlin"


def kotlin_sources(root: Path) -> str:
    return "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(root.rglob("*.kt"))
    )


class BackendModuleBoundaryTest(unittest.TestCase):
    def test_gateway_is_explicit_pure_jvm_module(self) -> None:
        settings = SETTINGS.read_text(encoding="utf-8")
        build = BACKEND_BUILD.read_text(encoding="utf-8")
        sources = kotlin_sources(BACKEND_MAIN)

        self.assertIn('include(":backend:gateway")', settings)
        self.assertIn('alias(libs.plugins.kotlin.jvm)', build)
        self.assertIn('api(project(":core:model"))', build)
        self.assertIn('implementation(libs.kotlinx.coroutines.core)', build)

        for forbidden in (
            'com.android.',
            'androidx.',
            'android.',
            'okhttp',
            'retrofit',
            ':core:location',
            ':core:database',
            ':core:network',
            ':forecast:',
            ':verification:',
        ):
            self.assertNotIn(forbidden, build + "\n" + sources)

    def test_android_app_does_not_depend_on_backend_implementation(self) -> None:
        app_build = APP_BUILD.read_text(encoding="utf-8")
        self.assertNotIn('project(":backend:gateway")', app_build)


if __name__ == "__main__":
    unittest.main()
