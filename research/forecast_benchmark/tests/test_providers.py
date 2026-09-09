from __future__ import annotations

import io
import unittest
import urllib.error
from unittest.mock import patch

from research.forecast_benchmark.model import Location
from research.forecast_benchmark.providers import (
    JsonHttpClient,
    OpenMeteoAdapter,
    OpenMeteoModel,
    open_meteo_run_parameter,
    parse_met_norway,
    parse_open_meteo,
)


LOCATION = Location(
    id="test",
    name="Test",
    latitude=59.94,
    longitude=30.31,
    timezone_id="Europe/Moscow",
    climate_note="fixture",
)


class OpenMeteoParserTest(unittest.TestCase):
    def test_parses_canonical_units_and_missing_values(self) -> None:
        payload = {
            "hourly": {
                "time": [1788948000],
                "temperature_2m": [12.5],
                "apparent_temperature": [11.0],
                "dew_point_2m": [8.0],
                "relative_humidity_2m": [70],
                "pressure_msl": [1012.4],
                "wind_speed_10m": [4.2],
                "wind_gusts_10m": [8.1],
                "wind_direction_10m": [350],
                "precipitation": [0.4],
                "precipitation_probability": [60],
                "cloud_cover": [80],
                "visibility": [12000],
            }
        }

        forecast = parse_open_meteo(
            payload,
            LOCATION,
            OpenMeteoModel("icon_global", "DWD_ICON"),
        )

        point = forecast.hourly[0]
        self.assertEqual(forecast.origin.provider, "OPEN_METEO")
        self.assertEqual(forecast.origin.model_family, "DWD_ICON")
        self.assertEqual(point.temperature_c, 12.5)
        self.assertEqual(point.wind_speed_mps, 4.2)
        self.assertEqual(point.precipitation_probability_percent, 60.0)

    def test_deterministic_requests_exclude_ensemble_probability(self) -> None:
        params = OpenMeteoAdapter._params(
            LOCATION,
            OpenMeteoModel("icon_global", "DWD_ICON"),
            72,
        )
        self.assertNotIn(
            "precipitation_probability",
            params["hourly"].split(","),
        )

    def test_single_run_parameter_uses_documented_utc_format(self) -> None:
        self.assertEqual(
            open_meteo_run_parameter("2026-08-01T00:00Z"),
            "2026-08-01T00:00",
        )
        self.assertEqual(
            open_meteo_run_parameter("2026-08-01T03:00+03:00"),
            "2026-08-01T00:00",
        )
        with self.assertRaises(ValueError):
            open_meteo_run_parameter("2026-08-01T00:00:30Z")

    def test_accepts_model_suffixed_fields(self) -> None:
        payload = {
            "hourly": {
                "time": [1788948000],
                "temperature_2m_ecmwf_ifs": [9.0],
            }
        }

        forecast = parse_open_meteo(
            payload,
            LOCATION,
            OpenMeteoModel("ecmwf_ifs", "ECMWF_IFS"),
        )
        self.assertEqual(forecast.hourly[0].temperature_c, 9.0)
        self.assertIsNone(forecast.hourly[0].pressure_sea_level_hpa)


class _FakeJsonResponse:
    status = 200

    def __init__(self, payload: bytes) -> None:
        self._payload = io.BytesIO(payload)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, *args):
        return self._payload.read(*args)


class JsonHttpClientTest(unittest.TestCase):
    def test_retries_transient_network_failure(self) -> None:
        sleeps: list[float] = []
        client = JsonHttpClient(
            max_attempts=2,
            backoff_seconds=0.25,
            sleep=sleeps.append,
        )
        with patch(
            "research.forecast_benchmark.providers.urllib.request.urlopen",
            side_effect=[
                urllib.error.URLError("temporary"),
                _FakeJsonResponse(b'{"ok": true}'),
            ],
        ):
            payload = client.get(
                "https://example.test/data",
                {"q": "test"},
            )

        self.assertEqual(payload, {"ok": True})
        self.assertEqual(sleeps, [0.25])

    def test_non_retryable_http_error_preserves_diagnostic_body(self) -> None:
        sleeps: list[float] = []
        client = JsonHttpClient(
            max_attempts=3,
            backoff_seconds=0.25,
            sleep=sleeps.append,
        )
        error = urllib.error.HTTPError(
            "https://example.test/data",
            400,
            "Bad Request",
            {},
            io.BytesIO(b'{"reason":"invalid run"}'),
        )
        with patch(
            "research.forecast_benchmark.providers.urllib.request.urlopen",
            side_effect=error,
        ) as mocked:
            with self.assertRaisesRegex(
                RuntimeError,
                "invalid run",
            ):
                client.get(
                    "https://example.test/data",
                    {"q": "test"},
                )

        self.assertEqual(mocked.call_count, 1)
        self.assertEqual(sleeps, [])


class MetNorwayParserTest(unittest.TestCase):
    def test_global_met_norway_is_marked_ecmwf_family(self) -> None:
        payload = {
            "properties": {
                "meta": {"updated_at": "2026-09-09T10:00:00Z"},
                "timeseries": [
                    {
                        "time": "2026-09-09T11:00:00Z",
                        "data": {
                            "instant": {
                                "details": {
                                    "air_temperature": 11.0,
                                    "relative_humidity": 76.0,
                                    "air_pressure_at_sea_level": 1008.0,
                                    "wind_speed": 4.0,
                                    "wind_from_direction": 20.0,
                                    "cloud_area_fraction": 90.0,
                                }
                            },
                            "next_1_hours": {
                                "details": {
                                    "precipitation_amount": 0.2,
                                }
                            },
                        },
                    }
                ],
            }
        }

        forecast = parse_met_norway(payload, LOCATION, 72)
        self.assertEqual(forecast.origin.provider, "MET_NORWAY")
        self.assertEqual(forecast.origin.model_family, "ECMWF_IFS")
        self.assertEqual(forecast.hourly[0].precipitation_mm, 0.2)


if __name__ == "__main__":
    unittest.main()
