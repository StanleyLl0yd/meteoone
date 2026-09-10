from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from research.grib_capabilities.grib2 import Grib2MessageInfo
from research.grib_capabilities.probe import NOAA_FIELDS, DownloadedSample
from research.grib_capabilities.probe_resilient import (
    NOAA_MAX_MESSAGES_PER_FIELD,
    _build_payload,
    probe_ecmwf_resilient,
    probe_noaa_resilient,
)


def _message() -> Grib2MessageInfo:
    return Grib2MessageInfo(
        offset=0,
        length=100,
        discipline=0,
        edition=2,
        section_numbers=(1, 3, 4, 5, 6, 7),
        grid_definition_template=0,
        product_definition_template=0,
        data_representation_template=0,
        parameter_category=0,
        parameter_number=0,
    )


def _ecmwf_entry(param: str, offset: int) -> dict[str, object]:
    return {
        "domain": "g",
        "date": "20260910",
        "time": "0000",
        "class": "od",
        "type": "fc",
        "stream": "oper",
        "step": "6",
        "levtype": "sfc",
        "param": param,
        "_offset": offset,
        "_length": 6,
    }


class ResilientProbeTest(unittest.TestCase):
    def test_noaa_measurement_accepts_bounded_multi_message_fields(self) -> None:
        messages = (_message(), _message())
        with tempfile.TemporaryDirectory() as directory:
            with (
                patch(
                    "research.grib_capabilities.probe_resilient._fetch_bounded",
                    return_value=(b"sample", 200, "https://nomads.ncep.noaa.gov/final"),
                ),
                patch(
                    "research.grib_capabilities.probe_resilient.inspect_grib2",
                    return_value=messages,
                ),
                patch("research.grib_capabilities.probe_resilient.time.sleep"),
            ):
                samples = probe_noaa_resilient(
                    datetime(2026, 9, 10, 6, tzinfo=timezone.utc),
                    6,
                    Path(directory),
                )

        self.assertEqual(len(samples), len(NOAA_FIELDS))
        self.assertTrue(all(len(sample.messages) == 2 for sample in samples))
        self.assertEqual(NOAA_MAX_MESSAGES_PER_FIELD, 4)

    def test_noaa_measurement_rejects_more_than_hard_cap(self) -> None:
        messages = tuple(_message() for _ in range(NOAA_MAX_MESSAGES_PER_FIELD + 1))
        with tempfile.TemporaryDirectory() as directory:
            with (
                patch(
                    "research.grib_capabilities.probe_resilient._fetch_bounded",
                    return_value=(b"sample", 200, "https://nomads.ncep.noaa.gov/final"),
                ),
                patch(
                    "research.grib_capabilities.probe_resilient.inspect_grib2",
                    return_value=messages,
                ),
            ):
                with self.assertRaises(RuntimeError):
                    probe_noaa_resilient(
                        datetime(2026, 9, 10, 6, tzinfo=timezone.utc),
                        6,
                        Path(directory),
                    )

    def test_ecmwf_measurement_retains_index_and_continues_after_field_failure(self) -> None:
        entries = [
            _ecmwf_entry("2t", 0),
            _ecmwf_entry("tcc", 6),
        ]
        index_raw = b'{"param":"2t"}\n{"param":"tcc"}\n'

        def fake_load(
            model_run: datetime,
            forecast_hour: int,
        ) -> tuple[str, str, list[dict[str, object]]]:
            del model_run, forecast_hour
            return (
                "https://data.ecmwf.int/example.index",
                "https://data.ecmwf.int/example.grib2",
                entries,
            )

        def fake_fetch(
            url: str,
            *,
            max_bytes: int,
            headers: dict[str, str] | None = None,
            require_status: int | None = None,
            expected_range: tuple[int, int] | None = None,
        ) -> tuple[bytes, int, str]:
            del max_bytes, require_status, expected_range
            if url.endswith(".index"):
                return index_raw, 200, url
            self.assertIsNotNone(headers)
            return b"sample", 206, url

        with tempfile.TemporaryDirectory() as directory:
            samples_dir = Path(directory)
            with (
                patch.dict(
                    "research.grib_capabilities.probe_resilient.ECMWF_FIELDS",
                    {
                        "temperature_2m": ("2t",),
                        "wind_gust_10m": ("missing",),
                        "total_cloud_cover": ("tcc",),
                    },
                    clear=True,
                ),
                patch(
                    "research.grib_capabilities.probe_resilient._load_ecmwf_index",
                    side_effect=fake_load,
                ),
                patch(
                    "research.grib_capabilities.probe_resilient._fetch_bounded",
                    side_effect=fake_fetch,
                ),
                patch(
                    "research.grib_capabilities.probe_resilient.inspect_grib2",
                    return_value=(_message(),),
                ),
            ):
                samples, errors, diagnostics = probe_ecmwf_resilient(
                    datetime(2026, 9, 10, 0, tzinfo=timezone.utc),
                    6,
                    samples_dir,
                )

            self.assertEqual(
                [sample.field for sample in samples],
                ["temperature_2m", "total_cloud_cover"],
            )
            self.assertEqual(len(errors), 1)
            self.assertEqual(errors[0]["field"], "wind_gust_10m")
            self.assertEqual(diagnostics["surface_param_values"], ["2t", "tcc"])
            self.assertEqual(diagnostics["index_size"], len(index_raw))
            self.assertEqual(diagnostics["index_status"], 200)
            self.assertEqual(
                (samples_dir / "ecmwf" / "index.jsonl").read_bytes(),
                index_raw,
            )

    def test_partial_payload_records_provider_failure(self) -> None:
        sample = DownloadedSample(
            provider="NOAA_NOMADS",
            field="temperature_2m",
            source_url="https://nomads.ncep.noaa.gov/source",
            final_url="https://nomads.ncep.noaa.gov/final",
            response_status=200,
            raw_size=100,
            raw_sha256="a" * 64,
            messages=(_message(),),
        )
        errors = [
            {
                "provider": "ECMWF_OPEN_DATA",
                "exception": "RuntimeError",
                "message": "example failure",
            }
        ]
        diagnostics = {
            "ECMWF_OPEN_DATA": {
                "surface_param_values": ["2t", "tcc"],
            }
        }
        payload = _build_payload(
            model_run=datetime(2026, 9, 10, 6, tzinfo=timezone.utc),
            forecast_hour=6,
            samples=[sample],
            errors=errors,
            diagnostics=diagnostics,
        )

        self.assertFalse(payload["success"])
        self.assertEqual(payload["schema_version"], 3)
        self.assertEqual(payload["errors"], errors)
        self.assertEqual(payload["diagnostics"], diagnostics)
        self.assertEqual(payload["sample_count"], 1)
        self.assertEqual(
            payload["providers"]["NOAA_NOMADS"]["data_representation_templates"],
            [0],
        )


if __name__ == "__main__":
    unittest.main()
