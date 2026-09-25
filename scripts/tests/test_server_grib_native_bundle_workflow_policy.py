from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "server-grib-native-bundle.yml"
BUILD_SCRIPT = ROOT / "scripts" / "build_server_grib_bundle.sh"


class ServerGribNativeBundlePolicyTest(unittest.TestCase):
    def test_server_runtime_is_pinned_and_never_published_by_workflow(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        build = BUILD_SCRIPT.read_text(encoding="utf-8")

        self.assertIn("permissions: {}", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertNotIn("contents: write", workflow)
        self.assertNotIn("gh release", workflow)
        self.assertNotIn("git push", workflow)
        self.assertIn("research/grib_decoder_candidates/pins.json", build)
        self.assertIn("meteoone_grib_jni.c", build)
        self.assertIn("-Wl,-rpath,'$ORIGIN'", build)
        self.assertIn("definition_file_count", build)


if __name__ == "__main__":
    unittest.main()
