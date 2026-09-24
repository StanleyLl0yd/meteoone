from pathlib import Path
import json
import unittest


ROOT = Path(__file__).resolve().parents[2]
SETTINGS = ROOT / "settings.gradle.kts"
BUILD = ROOT / "backend" / "server-native" / "build.gradle.kts"
MAIN = ROOT / "backend" / "server-native" / "src" / "main" / "kotlin"


class BackendServerNativeBoundaryTest(unittest.TestCase):
    def test_server_native_runtime_is_explicit_and_android_independent(self) -> None:
        settings = SETTINGS.read_text(encoding="utf-8")
        build = BUILD.read_text(encoding="utf-8")
        sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted(MAIN.rglob("*.kt"))
        )

        self.assertIn('include(":backend:server-native")', settings)
        self.assertIn('alias(libs.plugins.kotlin.jvm)', build)
        self.assertIn('api(project(":forecast:official"))', build)
        self.assertNotIn("android.", build + "\n" + sources)
        self.assertNotIn("androidx.", build + "\n" + sources)
        self.assertNotIn("System.getenv", sources)
        self.assertIn("EcCodesNativeLibrary.loadAbsolute", sources)
        self.assertIn("ServerEcCodesBundleVerifier.verify", sources)

        pins = json.loads(
            (ROOT / "research" / "grib_decoder_candidates" / "pins.json")
            .read_text(encoding="utf-8")
        )
        for commit in (
            pins["candidates"]["eccodes"]["commit"],
            pins["candidates"]["libaec"]["commit"],
            pins["tooling"]["ecbuild"]["commit"],
        ):
            self.assertIn(commit, sources)


if __name__ == "__main__":
    unittest.main()
