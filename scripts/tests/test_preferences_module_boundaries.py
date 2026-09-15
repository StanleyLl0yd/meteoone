from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
APP_MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
PREFERENCES_BUILD = ROOT / "core" / "preferences" / "build.gradle.kts"
TARGET_MODEL = ROOT / "core" / "model" / "src" / "main" / "kotlin" / "com" / "sl" / "meteoone" / "core" / "model" / "ForecastTarget.kt"
TARGET_STORE = ROOT / "core" / "preferences" / "src" / "main" / "kotlin" / "com" / "sl" / "meteoone" / "core" / "preferences" / "ForecastTargetStore.kt"


class ForecastTargetPreferenceBoundaryTest(unittest.TestCase):
    def test_preferences_depend_only_on_model_and_datastore(self) -> None:
        build = PREFERENCES_BUILD.read_text(encoding="utf-8")

        self.assertIn('api(project(":core:model"))', build)
        self.assertIn("androidx.datastore", build)
        for forbidden in (
            ':core:database',
            ':core:location',
            ':core:network',
            ':forecast:data',
            ':forecast:domain',
            ':forecast:repository',
        ):
            self.assertNotIn(f'project("{forbidden}")', build)

    def test_target_model_cannot_accept_android_location(self) -> None:
        model = TARGET_MODEL.read_text(encoding="utf-8")

        self.assertIn("val coordinate: ForecastCoordinate", model)
        self.assertNotIn("android.location", model)
        self.assertNotIn("Location", model)

    def test_public_store_exposes_only_target_model_and_flow(self) -> None:
        source = TARGET_STORE.read_text(encoding="utf-8")
        interface = source.split("interface ForecastTargetStore", 1)[1].split("internal class", 1)[0]

        self.assertIn("Flow<ForecastTarget?>", interface)
        self.assertIn("suspend fun set(target: ForecastTarget)", interface)
        self.assertIn("suspend fun clear()", interface)
        self.assertIn("fun android(context: Context): ForecastTargetStore", interface)
        for forbidden in (
            "DataStore<",
            "androidx.datastore",
            "ForecastLocation",
            "android.location.Location",
            "M1ForecastEngine",
        ):
            self.assertNotIn(forbidden, interface)

    def test_persisted_forecast_state_is_not_android_backed_up(self) -> None:
        manifest = APP_MANIFEST.read_text(encoding="utf-8")

        self.assertIn('android:allowBackup="false"', manifest)


if __name__ == "__main__":
    unittest.main()
