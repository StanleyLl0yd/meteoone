from __future__ import annotations

import math
import unittest

from research.forecast_benchmark.aggregate import aggregate_score_payloads


class AggregateScoreTest(unittest.TestCase):
    def test_combines_metrics_by_usable_counts(self) -> None:
        payloads = [
            {
                "location": "alpha",
                "scores": [
                    {
                        "model_family": "ECMWF_IFS",
                        "lead_bucket": "0-6h",
                        "matched_points": 3,
                        "temperature": {
                            "count": 2,
                            "mae": 1.0,
                            "bias": 0.5,
                            "rmse": 2.0,
                        },
                        "pressure": None,
                        "precipitation": None,
                        "wind_vector_error_mps": 1.0,
                        "wind_count": 2,
                        "precipitation_brier": 0.1,
                        "precipitation_brier_count": 1,
                    }
                ],
            },
            {
                "location": "beta",
                "scores": [
                    {
                        "model_family": "ECMWF_IFS",
                        "lead_bucket": "0-6h",
                        "matched_points": 2,
                        "temperature": {
                            "count": 1,
                            "mae": 4.0,
                            "bias": -1.0,
                            "rmse": 4.0,
                        },
                        "pressure": None,
                        "precipitation": None,
                        "wind_vector_error_mps": 4.0,
                        "wind_count": 1,
                        "precipitation_brier": 0.4,
                        "precipitation_brier_count": 2,
                    }
                ],
            },
        ]

        score = aggregate_score_payloads(payloads)[0]
        self.assertEqual(score.location_count, 2)
        self.assertEqual(score.matched_points, 5)
        assert score.temperature is not None
        self.assertEqual(score.temperature.count, 3)
        self.assertAlmostEqual(score.temperature.mae, 2.0)
        self.assertAlmostEqual(score.temperature.bias, 0.0)
        self.assertAlmostEqual(score.temperature.rmse, math.sqrt(8.0))
        self.assertEqual(score.wind_count, 3)
        self.assertAlmostEqual(score.wind_vector_error_mps or 0.0, 2.0)
        self.assertEqual(score.precipitation_brier_count, 3)
        self.assertAlmostEqual(score.precipitation_brier or 0.0, 0.3)

    def test_rejects_duplicate_location_payloads(self) -> None:
        payload = {"location": "alpha", "scores": []}
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            aggregate_score_payloads([payload, payload])


if __name__ == "__main__":
    unittest.main()
