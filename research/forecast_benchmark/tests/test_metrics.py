from __future__ import annotations

import math
import unittest

from research.forecast_benchmark.metrics import (
    brier_score,
    error_summary,
    lead_bucket,
    wind_vector_error_mps,
)


class ErrorSummaryTest(unittest.TestCase):
    def test_error_summary_ignores_missing_pairs(self) -> None:
        result = error_summary(
            [10.0, None, 15.0],
            [9.0, 12.0, 13.0],
        )
        assert result is not None
        self.assertEqual(result.count, 2)
        self.assertAlmostEqual(result.mae, 1.5)
        self.assertAlmostEqual(result.bias, 1.5)
        self.assertAlmostEqual(result.rmse, math.sqrt(2.5))

    def test_no_pairs_returns_none(self) -> None:
        self.assertIsNone(error_summary([None], [1.0]))


class BrierScoreTest(unittest.TestCase):
    def test_brier_score(self) -> None:
        score = brier_score([100.0, 0.0, 50.0], [True, False, True])
        self.assertAlmostEqual(score or -1.0, 1.0 / 12.0)

    def test_invalid_probability_rejected(self) -> None:
        with self.assertRaises(ValueError):
            brier_score([101.0], [True])


class WindVectorErrorTest(unittest.TestCase):
    def test_identical_wind_has_zero_error(self) -> None:
        value = wind_vector_error_mps(
            [5.0],
            [350.0],
            [5.0],
            [350.0],
        )
        self.assertAlmostEqual(value or -1.0, 0.0)

    def test_opposite_wind_is_large_error(self) -> None:
        value = wind_vector_error_mps(
            [5.0],
            [0.0],
            [5.0],
            [180.0],
        )
        self.assertAlmostEqual(value or -1.0, 10.0)


class LeadBucketTest(unittest.TestCase):
    def test_boundaries(self) -> None:
        self.assertEqual(lead_bucket(0), "0-6h")
        self.assertEqual(lead_bucket(6), "0-6h")
        self.assertEqual(lead_bucket(6.01), "6-24h")
        self.assertEqual(lead_bucket(24), "6-24h")
        self.assertEqual(lead_bucket(48), "24-48h")
        self.assertEqual(lead_bucket(72), "48-72h")
        self.assertIsNone(lead_bucket(72.01))


if __name__ == "__main__":
    unittest.main()
