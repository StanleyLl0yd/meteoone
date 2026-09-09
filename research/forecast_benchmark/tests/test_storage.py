from __future__ import annotations

import tempfile
import unittest
from datetime import date
from pathlib import Path

from research.forecast_benchmark.model import Forecast, HourlyPoint, Location, Origin
from research.forecast_benchmark.observations import (
    IsdStation,
    ObservationSeries,
    ObservedPoint,
)
from research.forecast_benchmark.storage import (
    read_forecasts,
    read_observations,
    write_forecasts,
    write_observations,
)


LOCATION = Location(
    id="test",
    name="Test",
    latitude=59.94,
    longitude=30.31,
    timezone_id="Europe/Moscow",
    climate_note="fixture",
)
STATION = IsdStation(
    station_id="26063099999",
    usaf="260630",
    wban="99999",
    name="Test Station",
    country="RU",
    icao="ULLI",
    latitude=59.8,
    longitude=30.3,
    elevation_m=24.0,
    begin=date(2000, 1, 1),
    end=date(2030, 12, 31),
)


class StorageRoundTripTest(unittest.TestCase):
    def test_forecast_jsonl_round_trip(self) -> None:
        forecast = Forecast(
            origin=Origin(
                provider="OPEN_METEO",
                model_family="ECMWF_IFS",
                model_id="ecmwf_ifs",
                model_run="2026-09-01T00:00:00Z",
            ),
            location=LOCATION,
            hourly=(
                HourlyPoint(
                    time="2026-09-01T01:00:00Z",
                    temperature_c=10.0,
                ),
            ),
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "forecast.jsonl"
            write_forecasts(path, [forecast])
            self.assertEqual(read_forecasts(path), (forecast,))

    def test_observation_json_round_trip(self) -> None:
        observations = ObservationSeries(
            source="NOAA_NCEI_ISD",
            station=STATION,
            points=(
                ObservedPoint(
                    time="2026-09-01T01:00:00Z",
                    temperature_c=9.0,
                ),
            ),
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "observations.json"
            write_observations(path, observations)
            self.assertEqual(read_observations(path), observations)


if __name__ == "__main__":
    unittest.main()
