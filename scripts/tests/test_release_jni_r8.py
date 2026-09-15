import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from scripts.verify_release_jni_r8 import (
    verify_aar_consumer_rules,
    verify_release_mapping,
)


RULES = """# JNI lookup
-keep class com.sl.meteoone.forecast.data.grib.EcCodesNativeBridge { *; }
-keep class com.sl.meteoone.forecast.data.grib.NativeGribMessage {
    <init>(long[], double[], double[]);
}
"""

MAPPING = """com.sl.meteoone.forecast.data.grib.EcCodesNativeBridge -> com.sl.meteoone.forecast.data.grib.EcCodesNativeBridge:
com.sl.meteoone.forecast.data.grib.NativeGribMessage -> com.sl.meteoone.forecast.data.grib.NativeGribMessage:
"""


class ReleaseJniR8VerifierTest(unittest.TestCase):
    def test_accepts_packaged_rules_and_unobfuscated_mapping(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            aar = root / "data.aar"
            mapping = root / "mapping.txt"
            with ZipFile(aar, "w") as archive:
                archive.writestr("proguard.txt", RULES)
            mapping.write_text(MAPPING, encoding="utf-8")

            verify_aar_consumer_rules(aar)
            verify_release_mapping(mapping)

    def test_rejects_missing_packaged_rules(self):
        with tempfile.TemporaryDirectory() as temporary:
            aar = Path(temporary) / "data.aar"
            with ZipFile(aar, "w"):
                pass

            with self.assertRaises(SystemExit):
                verify_aar_consumer_rules(aar)

    def test_rejects_obfuscated_jni_class(self):
        with tempfile.TemporaryDirectory() as temporary:
            mapping = Path(temporary) / "mapping.txt"
            mapping.write_text(
                MAPPING.replace(
                    "com.sl.meteoone.forecast.data.grib.NativeGribMessage -> com.sl.meteoone.forecast.data.grib.NativeGribMessage:",
                    "com.sl.meteoone.forecast.data.grib.NativeGribMessage -> a.b:",
                ),
                encoding="utf-8",
            )

            with self.assertRaises(SystemExit):
                verify_release_mapping(mapping)


if __name__ == "__main__":
    unittest.main()
