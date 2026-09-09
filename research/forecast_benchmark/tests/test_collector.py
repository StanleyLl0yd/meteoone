from __future__ import annotations

import unittest
from datetime import date

from research.forecast_benchmark.collector import collect_archived_runs, iter_runs
from research.forecast_benchmark.model import Forecast, HourlyPoint, Location, Origin
from research.forecast_benchmark.providers import OpenMeteoModel


LOCATION = Location(
    id="test",
    name="Test",
    latitude=59.94,
    longitude=30.31,
    timezone_id="Europe/Moscow",
    climate_note="fixture",
)
MODELS = (
    OpenMeteoModel("ecmwf_ifs", "ECMWF_IFS"),
    OpenMeteoModel("icon_global", "DWD_ICON"),
)


class FakeAdapter:
    def fetch_single_run(
        self,
        location: Location,
        model: OpenMeteoModel,
        run: str,
        forecast_hours: int = 72,
    ) -> Forecast:
        if model.model_id == "icon_global":
            raise RuntimeError("fixture failure")
        return Forecast(
            origin=Origin(
                provider="OPEN_METEO",
                model_family=model.model_family,
                model_id=model.model_id,
                model_run=run,
            ),
            location=location,
            hourly=(
                HourlyPoint(
                    time="2026-09-01T01:00:00Z",
                    temperature_c=10.0,
                ),
            ),
        )


class RunScheduleTest(unittest.TestCase):
    def test_iter_runs_is_inclusive_and_sorted(self) -> None:
        self.assertEqual(
            iter_runs(
                date(2026, 9, 1),
                date(2026, 9, 2),
                cycle_hours=(12, 0),
            ),
            (
                "2026-09-01T00:00Z",
                "2026-09-01T12:00Z",
                "2026-09-02T00:00Z",
                "2026-09-02T12:00Z",
            ),
        )


class DegradedCollectionTest(unittest.TestCase):
    def test_one_model_failure_does_not_discard_other_forecast(self) -> None:
        result = collect_archived_runs(
            FakeAdapter(),
            [LOCATION],
            ["2026-09-01T00:00Z"],
            models=MODELS,
        )
        self.assertEqual(len(result.forecasts), 1)
        self.assertEqual(result.forecasts[0].origin.model_family, "ECMWF_IFS")
        self.assertEqual(len(result.errors), 1)
        self.assertEqual(result.errors[0].model_id, "icon_global")
        self.assertEqual(result.errors[0].error_type, "RuntimeError")


if __name__ == "__main__":
    unittest.main()
