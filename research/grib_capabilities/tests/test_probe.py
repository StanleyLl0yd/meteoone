from __future__ import annotations

import bz2
import unittest

from research.grib_capabilities.grib2 import Grib2MessageInfo
from research.grib_capabilities.probe import (
    DownloadedSample,
    _decompress_bzip2_bounded,
    _forecast_hour,
    _parse_run,
    _template_summary,
    _validate_ecmwf_entry,
)


class ProbeHelpersTest(unittest.TestCase):
    def test_bzip2_decompression_is_bounded_and_rejects_trailing_data(self) -> None:
        original = b"GRIB" * 100
        compressed = bz2.compress(original)

        self.assertEqual(
            _decompress_bzip2_bounded(compressed, max_bytes=len(original)),
            original,
        )
        with self.assertRaises(RuntimeError):
            _decompress_bzip2_bounded(compressed, max_bytes=len(original) - 1)
        with self.assertRaises(RuntimeError):
            _decompress_bzip2_bounded(compressed + b"trailing", max_bytes=1024)

    def test_run_parser_requires_exact_operational_utc_cycle(self) -> None:
        parsed = _parse_run("2026-09-10T06:00Z")
        self.assertEqual(parsed.isoformat(), "2026-09-10T06:00:00+00:00")

        for invalid in (
            "2026-09-10T03:00Z",
            "2026-09-10T06:30Z",
            "2026-09-10T06:00",
        ):
            with self.assertRaises(Exception):
                _parse_run(invalid)

    def test_forecast_hour_requires_common_three_hour_step(self) -> None:
        self.assertEqual(_forecast_hour("3"), 3)
        self.assertEqual(_forecast_hour("72"), 72)
        for invalid in ("0", "1", "73", "x"):
            with self.assertRaises(Exception):
                _forecast_hour(invalid)

    def test_ecmwf_provenance_validation_rejects_mismatch(self) -> None:
        entry = {
            "domain": "g",
            "date": "20260910",
            "time": "0600",
            "class": "od",
            "type": "fc",
            "stream": "oper",
            "step": "6",
            "levtype": "sfc",
        }
        _validate_ecmwf_entry(
            entry,
            date="20260910",
            cycle="06",
            forecast_hour=6,
        )

        wrong = dict(entry, stream="enfo")
        with self.assertRaises(RuntimeError):
            _validate_ecmwf_entry(
                wrong,
                date="20260910",
                cycle="06",
                forecast_hour=6,
            )

    def test_template_summary_deduplicates_measured_requirements(self) -> None:
        first = Grib2MessageInfo(
            offset=0,
            length=100,
            discipline=0,
            edition=2,
            section_numbers=(1, 3, 4, 5, 6, 7),
            grid_definition_template=0,
            product_definition_template=0,
            data_representation_template=3,
            parameter_category=0,
            parameter_number=0,
        )
        second = Grib2MessageInfo(
            offset=0,
            length=120,
            discipline=0,
            edition=2,
            section_numbers=(1, 3, 4, 5, 6, 7),
            grid_definition_template=101,
            product_definition_template=8,
            data_representation_template=42,
            parameter_category=1,
            parameter_number=8,
        )
        samples = [
            DownloadedSample(
                provider="X",
                field="a",
                source_url="https://example.invalid/a",
                final_url="https://example.invalid/a",
                response_status=200,
                raw_size=100,
                raw_sha256="a" * 64,
                messages=(first,),
            ),
            DownloadedSample(
                provider="X",
                field="b",
                source_url="https://example.invalid/b",
                final_url="https://example.invalid/b",
                response_status=200,
                raw_size=120,
                raw_sha256="b" * 64,
                messages=(second, first),
            ),
        ]

        self.assertEqual(
            _template_summary(samples),
            {
                "grid_definition_templates": [0, 101],
                "product_definition_templates": [0, 8],
                "data_representation_templates": [3, 42],
            },
        )


if __name__ == "__main__":
    unittest.main()
