from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from research.grib_decoder_candidates.corpus import (
    EXPECTED_PROVIDERS,
    MANIFEST_PREFIX,
    REPRESENTATIVE_SAMPLES,
    verify_corpus,
)


def _sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


class CorpusVerificationTest(unittest.TestCase):
    def test_rejects_unsafe_manifest_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "SHA256SUMS").write_text(
                f"{'0' * 64}  {MANIFEST_PREFIX}../escape.grib2\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "Unsafe artifact path"):
                verify_corpus(root)

    def test_rejects_provider_template_envelope_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files: dict[str, bytes] = {
                relative: b"representative"
                for relative in REPRESENTATIVE_SAMPLES.values()
            }
            files["samples/ecmwf/index.jsonl"] = b"index\n"

            providers = json.loads(json.dumps(EXPECTED_PROVIDERS))
            providers["DWD_OPEN_DATA"]["grid_definition_templates"] = [0]
            evidence = {
                "schema_version": 3,
                "success": True,
                "errors": [],
                "model_run": "2026-09-10T18:00:00Z",
                "forecast_hour": 6,
                "sample_count": 28,
                "samples": [{} for _ in range(28)],
                "providers": providers,
            }
            files["evidence.json"] = json.dumps(evidence).encode("utf-8")

            manifest_lines: list[str] = []
            for relative, payload in sorted(files.items()):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(payload)
                manifest_lines.append(
                    f"{_sha256(payload)}  {MANIFEST_PREFIX}{relative}"
                )
            (root / "SHA256SUMS").write_text(
                "\n".join(manifest_lines) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                ValueError, "Measured decoder template envelope drifted"
            ):
                verify_corpus(root)


if __name__ == "__main__":
    unittest.main()
