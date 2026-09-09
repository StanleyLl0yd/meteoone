from __future__ import annotations

import argparse
import unittest
from datetime import date
from pathlib import Path

from research.forecast_benchmark.cli import _cycles_arg, _date_arg, parser


class CliArgumentTest(unittest.TestCase):
    def test_cycles_parser_accepts_explicit_cycles(self) -> None:
        self.assertEqual(_cycles_arg("0,6,12,18"), (0, 6, 12, 18))

    def test_cycles_parser_rejects_duplicates(self) -> None:
        with self.assertRaises(argparse.ArgumentTypeError):
            _cycles_arg("0,0")

    def test_date_parser_requires_iso_date(self) -> None:
        self.assertEqual(_date_arg("2026-09-01"), date(2026, 9, 1))
        with self.assertRaises(argparse.ArgumentTypeError):
            _date_arg("01.09.2026")

    def test_batch_defaults_to_one_utc_cycle_and_all_locations(self) -> None:
        args = parser().parse_args(
            [
                "batch-runs",
                "--start",
                "2026-09-01",
                "--end",
                "2026-09-02",
                "--output",
                "out.jsonl",
            ]
        )
        self.assertEqual(args.cycles, (0,))
        self.assertIsNone(args.location)
        self.assertEqual(args.hours, 72)
        self.assertEqual(args.output, Path("out.jsonl"))

    def test_aggregate_accepts_repeatable_score_files(self) -> None:
        args = parser().parse_args(
            [
                "aggregate",
                "--score",
                "moscow-score.json",
                "--score",
                "spb-score.json",
                "--output",
                "aggregate.json",
            ]
        )
        self.assertEqual(
            args.score,
            [Path("moscow-score.json"), Path("spb-score.json")],
        )
        self.assertEqual(args.output, Path("aggregate.json"))

    def test_station_rules_have_bounded_defaults(self) -> None:
        args = parser().parse_args(
            [
                "station",
                "--location",
                "moscow",
                "--start",
                "2026-09-01",
                "--end",
                "2026-09-07",
            ]
        )
        self.assertEqual(args.max_distance_km, 75.0)
        self.assertEqual(args.max_elevation_delta_m, 300.0)
        self.assertIsNone(args.target_elevation_m)

    def test_score_uses_half_hour_matching_tolerance(self) -> None:
        args = parser().parse_args(
            [
                "score",
                "--location",
                "moscow",
                "--forecasts",
                "forecast.jsonl",
                "--observations",
                "observations.json",
            ]
        )
        self.assertEqual(args.tolerance_minutes, 30)


if __name__ == "__main__":
    unittest.main()
