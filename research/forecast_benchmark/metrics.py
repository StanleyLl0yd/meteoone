from __future__ import annotations

import math
from dataclasses import dataclass
from statistics import fmean
from typing import Iterable, Sequence


@dataclass(frozen=True)
class ErrorSummary:
    count: int
    mae: float
    bias: float
    rmse: float


def error_summary(
    predicted: Sequence[float | None],
    observed: Sequence[float | None],
) -> ErrorSummary | None:
    if len(predicted) != len(observed):
        raise ValueError("predicted and observed lengths differ")

    errors = [
        p - o
        for p, o in zip(predicted, observed, strict=True)
        if p is not None and o is not None
    ]
    if not errors:
        return None

    return ErrorSummary(
        count=len(errors),
        mae=fmean(abs(value) for value in errors),
        bias=fmean(errors),
        rmse=math.sqrt(fmean(value * value for value in errors)),
    )


def brier_score(
    probabilities_percent: Sequence[float | None],
    observed_events: Sequence[bool | None],
) -> float | None:
    if len(probabilities_percent) != len(observed_events):
        raise ValueError("probability and observation lengths differ")

    scores = []
    for probability, observed in zip(
        probabilities_percent, observed_events, strict=True
    ):
        if probability is None or observed is None:
            continue
        if not 0.0 <= probability <= 100.0:
            raise ValueError("probability must be within 0..100")
        p = probability / 100.0
        outcome = 1.0 if observed else 0.0
        scores.append((p - outcome) ** 2)

    return fmean(scores) if scores else None


def wind_vector_error_mps(
    predicted_speed: Sequence[float | None],
    predicted_direction_deg: Sequence[float | None],
    observed_speed: Sequence[float | None],
    observed_direction_deg: Sequence[float | None],
) -> float | None:
    lengths = {
        len(predicted_speed),
        len(predicted_direction_deg),
        len(observed_speed),
        len(observed_direction_deg),
    }
    if len(lengths) != 1:
        raise ValueError("wind series lengths differ")

    errors = []
    for ps, pd, os, od in zip(
        predicted_speed,
        predicted_direction_deg,
        observed_speed,
        observed_direction_deg,
        strict=True,
    ):
        if None in (ps, pd, os, od):
            continue
        pu, pv = _wind_uv(float(ps), float(pd))
        ou, ov = _wind_uv(float(os), float(od))
        errors.append(math.hypot(pu - ou, pv - ov))

    return fmean(errors) if errors else None


def lead_bucket(hours: float) -> str | None:
    if 0.0 <= hours <= 6.0:
        return "0-6h"
    if 6.0 < hours <= 24.0:
        return "6-24h"
    if 24.0 < hours <= 48.0:
        return "24-48h"
    if 48.0 < hours <= 72.0:
        return "48-72h"
    return None


def _wind_uv(speed: float, direction_from_deg: float) -> tuple[float, float]:
    # Meteorological direction is where wind comes from.
    radians = math.radians(direction_from_deg)
    return (-speed * math.sin(radians), -speed * math.cos(radians))
