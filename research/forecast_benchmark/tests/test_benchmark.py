from __future__ import annotations

import unittest
from datetime import date

from research.forecast_benchmark.benchmark import score_forecasts
from research.forecast_benchmark.model import Forecast, HourlyPoint, Location, Origin
from research.forecast_benchmark.observations import (
    IsdStation,
    ObservationSeries,
    ObservedPoint,
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


class BenchmarkScoreTest(unittest.TestCase):
    def test_scores_all_four_lead_buckets(self) -> None:
        forecast = Forecast(
            origin=Origin(
                provider="OPEN_METEO",
                model_family="DWD_ICON",
                model_id="icon_global",
                model_run="2026-09-01T00:00:00Z",
            ),
            location=LOCATION,
            hourly=tuple(
                HourlyPoint(
                    time=time,
                    temperature_c=11.0,
                    pressure_sea_level_hpa=1001.0,
                    wind_speed_mps=5.0,
                    wind_direction_degrees=90.0,
                    precipitation_mm=1.0,
                    precipitation_probability_percent=80.0,
                )
                for time in (
                    "2026-09-01T06:00:00Z",
                    "2026-09-01T07:00:00Z",
                    "2026-09-02T01:00:00Z",
                    "2026-09-03T01:00:00Z",
                )
            ),
        )
        observations = ObservationSeries(
            source="NOAA_NCEI_ISD",
            station=STATION,
            points=tuple(
                ObservedPoint(
                    time=time,
                    temperature_c=10.0,
                    pressure_sea_level_hpa=1000.0,
                    wind_speed_mps=5.0,
                    wind_direction_degrees=90.0,
                    precipitation_mm=0.0,
                    precipitation_trace=index == 0,
                )
                for index, time in enumerate(
                    (
                        "2026-09-01T06:00:00Z",
                        "2026-09-01T07:00:00Z",
                        "2026-09-02T01:00:00Z",
                        "2026-09-03T01:00:00Z",
                    )
                )
            ),
        )

        scores = score_forecasts([forecast], observations)
        self.assertEqual(
            [score.lead_bucket for score in scores],
            ["0-6h", "6-24h", "24-48h", "48-72h"],
        )
        for score in scores:
            self.assertEqual(score.matched_points, 1)
            assert score.temperature is not None
            self.assertEqual(score.temperature.mae, 1.0)
            self.assertEqual(score.wind_vector_error_mps, 0.0)
            self.assertEqual(score.precipitation_brier_count, 1)

        first = next(score for score in scores if score.lead_bucket == "0-6h")
        self.assertAlmostEqual(first.precipitation_brier or -1.0, 0.04)

    def test_unsorted_observations_are_normalized_before_matching(self) -> None:
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
                    time="2026-09-01T06:00:00Z",
                    temperature_c=10.0,
                ),
            ),
        )
        observations = ObservationSeries(
            source="NOAA_NCEI_ISD",
            station=STATION,
            points=(
                ObservedPoint(
                    time="2026-09-01T07:00:00Z",
                    temperature_c=20.0,
                ),
                ObservedPoint(
                    time="2026-09-01T06:05:00Z",
                    temperature_c=9.0,
                ),
                ObservedPoint(
                    time="2026-09-01T05:55:00Z",
                    temperature_c=8.0,
                ),
            ),
        )

        scores = score_forecasts([forecast], observations)
        self.assertEqual(len(scores), 1)
        assert scores[0].temperature is not None
        self.assertEqual(scores[0].temperature.mae, 2.0)

    def test_nearest_observation_respects_tolerance(self) -> None:
        forecast = Forecast(
            origin=Origin(
                provider="OPEN_METEO",
                model_family="NOAA_GFS",
                model_id="ncep_gfs_global",
                model_run="2026-09-01T00:00:00Z",
            ),
            location=LOCATION,
            hourly=(
                HourlyPoint(
                    time="2026-09-01T06:00:00Z",
                    temperature_c=10.0,
                ),
            ),
        )
        observations = ObservationSeries(
            source="NOAA_NCEI_ISD",
            station=STATION,
            points=(
                ObservedPoint(
                    time="2026-09-01T06:31:00Z",
                    temperature_c=10.0,
                ),
            ),
        )
        self.assertEqual(
            score_forecasts([forecast], observations, tolerance_minutes=30),
            (),
        )


if __name__ == "__main__":
    unittest.main()
