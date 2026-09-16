from __future__ import annotations

import base64
import hashlib
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts" / "export_aab_upload_certificate.py"
SPEC = importlib.util.spec_from_file_location("export_aab_upload_certificate", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ExportAabUploadCertificateTest(unittest.TestCase):
    def test_normalize_fingerprint_accepts_colons_and_case(self) -> None:
        raw = ":".join(["AB"] * 32)
        self.assertEqual(MODULE.normalize_fingerprint(raw), "ab" * 32)

    def test_normalize_fingerprint_rejects_wrong_length(self) -> None:
        with self.assertRaises(ValueError):
            MODULE.normalize_fingerprint("aa:bb")

    def test_export_writes_single_certificate_and_checks_hash(self) -> None:
        der = b"synthetic-public-certificate-bytes"
        encoded = base64.b64encode(der).decode("ascii")
        pem = (
            "-----BEGIN CERTIFICATE-----\n"
            + encoded
            + "\n-----END CERTIFICATE-----\n"
        ).encode("ascii")
        expected = hashlib.sha256(der).hexdigest()

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            aab = root / "app.aab"
            output = root / "uploadcert.pem"
            with ZipFile(aab, "w") as archive:
                archive.writestr("META-INF/KEY0.RSA", b"pkcs7-placeholder")

            completed = subprocess.CompletedProcess(
                args=["keytool"], returncode=0, stdout=pem, stderr=b""
            )
            with mock.patch.object(MODULE.subprocess, "run", return_value=completed) as run:
                actual = MODULE.export_certificate(aab, output, expected.upper())

            self.assertEqual(actual, expected)
            self.assertEqual(output.read_bytes(), pem)
            run.assert_called_once()

    def test_export_rejects_multiple_signature_blocks_before_keytool(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            aab = root / "app.aab"
            with ZipFile(aab, "w") as archive:
                archive.writestr("META-INF/KEY0.RSA", b"one")
                archive.writestr("META-INF/KEY1.RSA", b"two")

            with mock.patch.object(MODULE.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "exactly one JAR signature block"):
                    MODULE.export_certificate(aab, root / "out.pem", None)
            run.assert_not_called()

    def test_export_rejects_fingerprint_mismatch_without_writing_output(self) -> None:
        der = b"synthetic-public-certificate-bytes"
        encoded = base64.b64encode(der).decode("ascii")
        pem = (
            "-----BEGIN CERTIFICATE-----\n"
            + encoded
            + "\n-----END CERTIFICATE-----\n"
        ).encode("ascii")

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            aab = root / "app.aab"
            output = root / "uploadcert.pem"
            with ZipFile(aab, "w") as archive:
                archive.writestr("META-INF/KEY0.RSA", b"pkcs7-placeholder")

            completed = subprocess.CompletedProcess(
                args=["keytool"], returncode=0, stdout=pem, stderr=b""
            )
            with mock.patch.object(MODULE.subprocess, "run", return_value=completed):
                with self.assertRaisesRegex(ValueError, "certificate SHA-256 mismatch"):
                    MODULE.export_certificate(aab, output, "00" * 32)

            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
