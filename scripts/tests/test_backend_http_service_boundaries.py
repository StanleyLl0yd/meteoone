from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "backend" / "http-service" / "build.gradle.kts"
SOURCE_ROOT = ROOT / "backend" / "http-service" / "src" / "main" / "kotlin"


class BackendHttpServiceBoundaryTest(unittest.TestCase):
    def test_http_service_is_thin_server_transport(self) -> None:
        build = BUILD.read_text(encoding="utf-8")
        sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in SOURCE_ROOT.rglob("*.kt")
        )

        self.assertIn('project(":backend:contract")', build)
        self.assertIn('project(":backend:orchestration")', build)
        self.assertIn("ktor.server.core", build)
        self.assertNotIn("com.android.", build)
        self.assertNotIn("androidx.", build)

        self.assertNotIn("backend.provideradapter", sources)
        self.assertNotIn("backend.provider.", sources)
        self.assertNotIn("forecast.data.", sources)
        self.assertNotIn("android.", sources)
        self.assertNotIn("androidx.", sources)

    def test_android_app_does_not_depend_on_http_service(self) -> None:
        app_build = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertNotIn('project(":backend:http-service")', app_build)


if __name__ == "__main__":
    unittest.main()
