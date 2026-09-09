from __future__ import annotations

import json
import tempfile
import unittest
import urllib.error
from datetime import date
from pathlib import Path
from unittest.mock import patch

from research.forecast_benchmark.model import Location
from research.forecast_benchmark.observations import (
    ObservationSeries,
    ObservedPoint,
    Wis2Station,
)
from research.forecast_benchmark.wis2_observations import (
    DATASET_ID,
    RawJsonResponse,
    RoshydrometWis2Adapter,
    Wis2HttpClient,
    load_wis2_stations,
    parse_roshydromet_synop,
    select_wis2_station,
    validate_synop_coverage,
)


LOCATION = Location(
    id="saint-petersburg",
    name="Saint Petersburg",
    latitude=59.9386,
    longitude=30.3141,
    timezone_id="Europe/Moscow",
    climate_note="fixture",
)
STATION = Wis2Station(
    station_id="0-20000-0-26063",
    name="Saint Petersburg SYNOP",
    country="RU",
    latitude=59.9667,
    longitude=30.3,
    elevation_m=3.0,
    begin=date(2026, 7, 1),
    dataset_id=DATASET_ID,
)


def feature(
    report_time: str,
    name: str,
    value: float,
    units: str,
    *,
    phenomenon_time: str | None = None,
) -> dict[str, object]:
    return {
        "type": "Feature",
        "geometry": {
            "type": "Point",
            "coordinates": [30.3, 59.9667, 3.0],
        },
        "properties": {
            "wigos_station_identifier": STATION.station_id,
            "reportTime": report_time,
            "phenomenonTime": phenomenon_time or report_time,
            "reportId": (
                f"{STATION.station_id}-"
                f"{report_time[0:13].replace('-', '').replace(':', '')}"
            ),
            "name": name,
            "units": units,
            "value": value,
        },
    }


def day_payload() -> dict[str, object]:
    features: list[dict[str, object]] = []
    for hour in range(0, 24, 3):
        report_time = f"2026-08-01T{hour:02d}:00:00Z"
        features.extend(
            [
                feature(report_time, "air_temperature", 20.0 + hour / 10, "Celsius"),
                feature(
                    report_time,
                    "pressure_reduced_to_mean_sea_level",
                    1012.0,
                    "hPa",
                ),
                feature(
                    report_time,
                    "wind_speed",
                    3.0,
                    "m/s",
                    phenomenon_time=(
                        f"2026-08-01T{(hour - 1) % 24:02d}:50:00Z/"
                        f"{report_time}"
                    ),
                ),
                feature(
                    report_time,
                    "wind_direction",
                    270.0,
                    "deg",
                ),
            ]
        )
    features.append(
        feature(
            "2026-08-01T18:00:00Z",
            "total_precipitation_or_total_water_equivalent",
            2.5,
            "kg m-2",
            phenomenon_time=(
                "2026-08-01T06:00:00Z/"
                "2026-08-01T18:00:00Z"
            ),
        )
    )
    return {
        "type": "FeatureCollection",
        "numberMatched": len(features),
        "numberReturned": len(features),
        "features": features,
    }


class Wis2ObservationParserTest(unittest.TestCase):
    def test_parses_deterministic_fields_and_interval_precipitation(self) -> None:
        series = parse_roshydromet_synop(day_payload(), STATION)

        self.assertEqual(len(series.points), 8)
        self.assertEqual(series.points[0].temperature_c, 20.0)
        self.assertEqual(series.points[0].pressure_sea_level_hpa, 1012.0)
        self.assertEqual(series.points[0].wind_speed_mps, 3.0)
        self.assertEqual(series.points[0].wind_direction_degrees, 270.0)
        self.assertIsNone(series.points[0].precipitation_mm)
        self.assertEqual(len(series.precipitation_intervals), 1)
        interval = series.precipitation_intervals[0]
        self.assertEqual(interval.amount_mm, 2.5)
        self.assertEqual(interval.start_time, "2026-08-01T06:00:00Z")
        self.assertEqual(interval.end_time, "2026-08-01T18:00:00Z")

    def test_rejects_wrong_units_and_conflicting_duplicates(self) -> None:
        payload = day_payload()
        features = payload["features"]
        assert isinstance(features, list)
        features.append(
            feature(
                "2026-08-01T00:00:00Z",
                "wind_speed",
                8.0,
                "m/s",
            )
        )
        features.append(
            feature(
                "2026-08-01T00:00:00Z",
                "air_temperature",
                293.0,
                "K",
            )
        )

        series = parse_roshydromet_synop(payload, STATION)
        self.assertIsNone(series.points[0].wind_speed_mps)
        self.assertEqual(series.points[0].temperature_c, 20.0)

    def test_rejects_foreign_station_features(self) -> None:
        payload = day_payload()
        features = payload["features"]
        assert isinstance(features, list)
        properties = features[0]["properties"]
        assert isinstance(properties, dict)
        properties["wigos_station_identifier"] = "0-20000-0-99999"

        with self.assertRaises(ValueError):
            parse_roshydromet_synop(payload, STATION)


class Wis2CoverageTest(unittest.TestCase):
    def test_requires_actual_core_field_coverage(self) -> None:
        series = parse_roshydromet_synop(day_payload(), STATION)
        validate_synop_coverage(
            series,
            date(2026, 8, 1),
            date(2026, 8, 1),
        )

        broken = ObservationSeries(
            source=series.source,
            station=series.station,
            points=series.points[:7],
        )
        with self.assertRaises(LookupError):
            validate_synop_coverage(
                broken,
                date(2026, 8, 1),
                date(2026, 8, 1),
            )

    def test_verified_station_keeps_distance_and_elevation_gates(self) -> None:
        match = select_wis2_station(
            LOCATION,
            STATION,
            date(2026, 8, 1),
            date(2026, 8, 31),
            target_elevation_m=10.0,
        )
        self.assertLess(match.distance_km, 4.0)
        self.assertEqual(match.elevation_delta_m, 7.0)

        with self.assertRaises(LookupError):
            select_wis2_station(
                LOCATION,
                STATION,
                date(2026, 8, 1),
                date(2026, 8, 31),
                max_distance_km=1.0,
            )

    def test_station_file_has_unique_entries_for_all_locations(self) -> None:
        stations = load_wis2_stations()
        self.assertEqual(len(stations), 10)
        self.assertEqual(
            stations["saint-petersburg"].station_id,
            STATION.station_id,
        )


class _FakeResponse:
    status = 200

    def __init__(self, payload: dict[str, object]) -> None:
        self.body = json.dumps(payload).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self) -> bytes:
        return self.body

    def geturl(self) -> str:
        return "https://example.test/final"


class Wis2HttpClientTest(unittest.TestCase):
    def test_retries_transient_network_failure(self) -> None:
        sleeps: list[float] = []
        client = Wis2HttpClient(
            max_attempts=2,
            backoff_seconds=0.25,
            sleep=sleeps.append,
        )
        with patch(
            "research.forecast_benchmark.wis2_observations.urllib.request.urlopen",
            side_effect=[
                urllib.error.URLError("temporary"),
                _FakeResponse({"features": []}),
            ],
        ):
            response = client.get_json(
                "https://example.test/items",
                {"f": "json"},
            )

        self.assertEqual(response.payload, {"features": []})
        self.assertEqual(sleeps, [0.25])


class _FakeHttp:
    def __init__(self, payload: dict[str, object]) -> None:
        self.payload = payload

    def get_json(self, _url, _params):
        body = json.dumps(self.payload).encode()
        return RawJsonResponse(
            body=body,
            payload=self.payload,
            final_url="https://example.test/items",
        )


class Wis2AdapterTest(unittest.TestCase):
    def test_detects_truncated_collection(self) -> None:
        payload = day_payload()
        payload["numberMatched"] = int(payload["numberReturned"]) + 1
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "stations.json"
            path.write_text(
                json.dumps(
                    [
                        {
                            "location_id": LOCATION.id,
                            "station_id": STATION.station_id,
                            "name": STATION.name,
                            "country": STATION.country,
                            "latitude": STATION.latitude,
                            "longitude": STATION.longitude,
                            "elevation_m": STATION.elevation_m,
                            "begin": STATION.begin.isoformat(),
                            "dataset_id": STATION.dataset_id,
                        }
                    ]
                )
            )
            adapter = RoshydrometWis2Adapter(
                http=_FakeHttp(payload),
                stations_path=path,
            )
            with self.assertRaises(RuntimeError):
                adapter.fetch_period(
                    STATION,
                    date(2026, 8, 1),
                    date(2026, 8, 1),
                )


if __name__ == "__main__":
    unittest.main()
