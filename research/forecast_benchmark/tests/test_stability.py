from __future__ import annotations

import unittest
from datetime import date

from research.forecast_benchmark.model import (
    Forecast,
    HourlyPoint,
    Location,
    Origin,
)
from research.forecast_benchmark.observations import (
    IsdStation,
    ObservationSeries,
    ObservedPoint,
)
from research.forecast_benchmark.stability import (
    analyze_forecast_stability,
    analyze_stability,
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


def forecast(
    model_family: str,
    model_id: str,
    run: str,
    valid_time: str,
    temperature: float,
    pressure: float,
    wind_speed: float,
) -> Forecast:
    return Forecast(
        origin=Origin(
            provider="OPEN_METEO",
            model_family=model_family,
            model_id=model_id,
            model_run=run,
        ),
        location=LOCATION,
        hourly=(
            HourlyPoint(
                time=valid_time,
                temperature_c=temperature,
                pressure_sea_level_hpa=pressure,
                wind_speed_mps=wind_speed,
                wind_direction_degrees=90.0,
            ),
        ),
    )


class ForecastStabilityTest(unittest.TestCase):
    def test_builds_required_time_splits_and_winner_evidence(self) -> None:
        forecasts = []
        for run, valid_time in (
            ("2026-08-01T00:00:00Z", "2026-08-01T06:00:00Z"),
            ("2026-08-16T00:00:00Z", "2026-08-16T06:00:00Z"),
        ):
            forecasts.extend(
                [
                    forecast(
                        "DWD_ICON",
                        "icon_global",
                        run,
                        valid_time,
                        11.0,
                        1003.0,
                        4.0,
                    ),
                    forecast(
                        "ECMWF_IFS",
                        "ecmwf_ifs",
                        run,
                        valid_time,
                        12.0,
                        1001.0,
                        3.0,
                    ),
                    forecast(
                        "NOAA_GFS",
                        "ncep_gfs_global",
                        run,
                        valid_time,
                        13.0,
                        1002.0,
                        2.0,
                    ),
                ]
            )

        observations = ObservationSeries(
            source="fixture",
            station=STATION,
            points=(
                ObservedPoint(
                    time="2026-08-01T06:00:00Z",
                    temperature_c=10.0,
                    pressure_sea_level_hpa=1000.0,
                    wind_speed_mps=2.0,
                    wind_direction_degrees=90.0,
                ),
                ObservedPoint(
                    time="2026-08-16T06:00:00Z",
                    temperature_c=10.0,
                    pressure_sea_level_hpa=1000.0,
                    wind_speed_mps=2.0,
                    wind_direction_degrees=90.0,
                ),
            ),
        )

        result = analyze_forecast_stability(
            forecasts,
            {LOCATION.id: observations},
        )

        self.assertEqual(
            result["forecast_counts_by_split"],
            {
                "all": 6,
                "first-half": 3,
                "second-half": 3,
                "odd": 3,
                "even": 3,
            },
        )
        winners = result["winner_counts_across_splits_and_leads"]
        self.assertEqual(winners["temperature_mae"], {"DWD_ICON": 5})
        self.assertEqual(winners["pressure_mae"], {"ECMWF_IFS": 5})
        self.assertEqual(winners["wind_vector_error_mps"], {"NOAA_GFS": 5})

        location_wins = result["location_lead_winners"]
        self.assertEqual(
            location_wins["temperature_mae"],
            {"cells": 1, "ties": 0, "wins": {"DWD_ICON": 1}},
        )

    def test_requires_observations_for_every_forecast_location(self) -> None:
        value = forecast(
            "DWD_ICON",
            "icon_global",
            "2026-08-01T00:00:00Z",
            "2026-08-01T06:00:00Z",
            11.0,
            1003.0,
            4.0,
        )
        with self.assertRaisesRegex(ValueError, "Missing observations"):
            analyze_forecast_stability([value], {})


class StabilitySummaryTest(unittest.TestCase):
    def test_exact_ties_are_not_counted_as_model_wins(self) -> None:
        tied_score = {
            "lead_bucket": "0-6h",
            "matched_points": 1,
            "temperature": {
                "count": 1,
                "mae": 1.0,
                "bias": 0.0,
                "rmse": 1.0,
            },
            "pressure": None,
            "precipitation": None,
            "wind_vector_error_mps": None,
            "wind_count": 0,
            "precipitation_brier": None,
            "precipitation_brier_count": 0,
        }
        aggregate = {
            "scores": [
                {"model_family": "DWD_ICON", **tied_score},
                {"model_family": "ECMWF_IFS", **tied_score},
            ],
        }
        location = {
            "location": "test",
            "scores": aggregate["scores"],
        }

        result = analyze_stability({"all": aggregate}, [location])

        winner = result["split_bucket_winners"]["all"]["0-6h"][
            "temperature_mae"
        ]
        self.assertIsNone(winner["winner"])
        self.assertEqual(
            winner["tied_models"],
            ["DWD_ICON", "ECMWF_IFS"],
        )
        self.assertEqual(
            result["winner_counts_across_splits_and_leads"][
                "temperature_mae"
            ],
            {},
        )
        self.assertEqual(
            result["location_lead_winners"]["temperature_mae"],
            {"cells": 1, "ties": 1, "wins": {}},
        )

    def test_ranks_precipitation_interval_skill_when_available(self) -> None:
        def score(model_family: str, mae: float) -> dict[str, object]:
            return {
                "model_family": model_family,
                "lead_bucket": "6-24h",
                "matched_points": 0,
                "temperature": None,
                "pressure": None,
                "precipitation": {
                    "count": 3,
                    "mae": mae,
                    "bias": 0.0,
                    "rmse": mae,
                },
                "wind_vector_error_mps": None,
                "wind_count": 0,
                "precipitation_brier": None,
                "precipitation_brier_count": 0,
            }

        scores = [
            score("ECMWF_IFS", 0.8),
            score("DWD_ICON", 1.2),
        ]
        result = analyze_stability(
            {"all": {"scores": scores}},
            [{"location": "test", "scores": scores}],
        )

        winner = result["split_bucket_winners"]["all"]["6-24h"][
            "precipitation_mae"
        ]
        self.assertEqual(winner["winner"], "ECMWF_IFS")
        self.assertEqual(
            result["winner_counts_across_splits_and_leads"][
                "precipitation_mae"
            ],
            {"ECMWF_IFS": 1},
        )
        self.assertEqual(
            result["location_lead_winners"]["precipitation_mae"],
            {"cells": 1, "ties": 0, "wins": {"ECMWF_IFS": 1}},
        )

    def test_rejects_duplicate_location_payloads(self) -> None:
        aggregate = {
            "scores": [],
        }
        location = {
            "location": "test",
            "scores": [],
        }
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            analyze_stability(
                {"all": aggregate},
                [location, location],
            )


if __name__ == "__main__":
    unittest.main()
