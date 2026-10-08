"""Enforce Android-only MeteoOne product architecture without a proprietary backend."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class AndroidOnlyArchitecturePolicyTest(unittest.TestCase):
    def test_backend_modules_and_server_workflows_do_not_exist(self):
        self.assertFalse((ROOT / "backend").exists())
        self.assertFalse((ROOT / "scripts/build_server_grib_bundle.sh").exists())
        self.assertFalse((ROOT / ".github/workflows/server-grib-native-bundle.yml").exists())
        self.assertFalse((ROOT / "docs/architecture/BACKEND_HTTP_SERVICE.md").exists())

    def test_build_graph_and_ci_have_no_server_modules(self):
        settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        data = (ROOT / "forecast/data/build.gradle.kts").read_text(encoding="utf-8")
        self.assertNotIn('include(":backend:', settings)
        agent_contract = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
        self.assertNotIn(":backend:", agent_contract)
        self.assertIn("Android-only", agent_contract)
        self.assertNotIn(":backend:", ci)
        self.assertNotIn("project(\":backend:", data)
        dependency_catalog = (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
        self.assertNotIn("ktor-server-", dependency_catalog)
        self.assertNotIn("io.ktor:", dependency_catalog)
        for module in (":forecast:data:testDebugUnitTest", ":forecast:repository:testDebugUnitTest",
                       ":verification:domain:test", ":verification:data:test",
                       ":app:assembleDebug", ":app:bundleRelease"):
            self.assertIn(module, ci)

    def test_app_can_only_refresh_directly_on_device(self):
        app = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        activity = (ROOT / "app/src/main/kotlin/com/sl/meteoone/MainActivity.kt").read_text(encoding="utf-8")
        facade = (ROOT / "forecast/repository/src/main/kotlin/com/sl/meteoone/forecast/repository/ForecastRepository.kt").read_text(encoding="utf-8")
        impl = (ROOT / "forecast/repository/src/main/kotlin/com/sl/meteoone/forecast/repository/DefaultForecastRepository.kt").read_text(encoding="utf-8")
        release = (ROOT / ".github/workflows/signed-release-build.yml").read_text(encoding="utf-8")
        for source in (app, activity, facade, impl, release):
            self.assertNotIn("METEOONE_BACKEND_FORECAST_URL", source)
            self.assertNotIn("BACKEND_FORECAST_URL", source)
            self.assertNotIn("BackendForecastClient", source)
            self.assertNotIn("BackendForecastRefreshSource", source)
        self.assertIn("ForecastRepository.android(appContext)", activity)
        self.assertIn("M1ForecastEngine.android(", impl)
        self.assertIn("M4ForecastRefreshSource(", impl)
        self.assertIn("productionM4VerificationEvidenceCoordinator(", impl)
        self.assertFalse((ROOT / "forecast/data/src/main/kotlin/com/sl/meteoone/forecast/data/backend").exists())

    def test_native_android_decoder_and_offline_store_are_retained(self):
        self.assertTrue((ROOT / "forecast/data/native/meteoone_grib_jni.c").is_file())
        self.assertTrue((ROOT / "core/database").is_dir())
        self.assertTrue((ROOT / "verification/data").is_dir())
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("Verify forecast data native AAR", ci)
        self.assertIn("Verify Room schema is committed", ci)
        self.assertIn("Verify release JNI R8 boundary", ci)


if __name__ == "__main__":
    unittest.main()
