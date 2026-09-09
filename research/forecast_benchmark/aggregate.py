from __future__ import annotations

import math
from dataclasses import asdict, dataclass
from typing import Any, Iterable, Mapping

from .benchmark import BucketScore
from .metrics import ErrorSummary


@dataclass(frozen=True)
class AggregateBucketScore:
    model_family: str
    lead_bucket: str
    location_count: int
    matched_points: int
    temperature: ErrorSummary | None
    pressure: ErrorSummary | None
    precipitation: ErrorSummary | None
    wind_vector_error_mps: float | None
    wind_count: int
    precipitation_brier: float | None
    precipitation_brier_count: int

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


def aggregate_score_payloads(
    payloads: Iterable[Mapping[str, Any]],
) -> tuple[AggregateBucketScore, ...]:
    grouped: dict[tuple[str, str], list[tuple[str, BucketScore]]] = {}

    for payload in payloads:
        location = str(payload.get("location") or "").strip()
        raw_scores = payload.get("scores")
        if not location:
            raise ValueError("Score payload has no location")
        if not isinstance(raw_scores, list):
            raise ValueError(f"Score payload for {location} has no scores array")

        for raw_score in raw_scores:
            if not isinstance(raw_score, Mapping):
                raise ValueError(f"Invalid score entry for {location}")
            score = bucket_score_from_dict(raw_score)
            grouped.setdefault(
                (score.model_family, score.lead_bucket),
                [],
            ).append((location, score))

    bucket_order = {"0-6h": 0, "6-24h": 1, "24-48h": 2, "48-72h": 3}
    keys = sorted(
        grouped,
        key=lambda key: (key[0], bucket_order.get(key[1], 999), key[1]),
    )
    return tuple(
        _aggregate_group(model_family, bucket, grouped[(model_family, bucket)])
        for model_family, bucket in keys
    )


def bucket_score_from_dict(payload: Mapping[str, Any]) -> BucketScore:
    return BucketScore(
        model_family=str(payload["model_family"]),
        lead_bucket=str(payload["lead_bucket"]),
        matched_points=int(payload["matched_points"]),
        temperature=_error_summary_from_dict(payload.get("temperature")),
        pressure=_error_summary_from_dict(payload.get("pressure")),
        precipitation=_error_summary_from_dict(payload.get("precipitation")),
        wind_vector_error_mps=_optional_float(
            payload.get("wind_vector_error_mps")
        ),
        wind_count=int(payload.get("wind_count") or 0),
        precipitation_brier=_optional_float(
            payload.get("precipitation_brier")
        ),
        precipitation_brier_count=int(
            payload.get("precipitation_brier_count") or 0
        ),
    )


def _aggregate_group(
    model_family: str,
    bucket: str,
    values: list[tuple[str, BucketScore]],
) -> AggregateBucketScore:
    location_count = len({location for location, _ in values})
    scores = [score for _, score in values]

    wind_count = sum(score.wind_count for score in scores)
    wind_vector_error = _weighted_mean(
        (
            (score.wind_vector_error_mps, score.wind_count)
            for score in scores
        )
    )

    brier_count = sum(score.precipitation_brier_count for score in scores)
    brier = _weighted_mean(
        (
            (score.precipitation_brier, score.precipitation_brier_count)
            for score in scores
        )
    )

    return AggregateBucketScore(
        model_family=model_family,
        lead_bucket=bucket,
        location_count=location_count,
        matched_points=sum(score.matched_points for score in scores),
        temperature=_combine_error_summaries(
            score.temperature for score in scores
        ),
        pressure=_combine_error_summaries(
            score.pressure for score in scores
        ),
        precipitation=_combine_error_summaries(
            score.precipitation for score in scores
        ),
        wind_vector_error_mps=wind_vector_error,
        wind_count=wind_count,
        precipitation_brier=brier,
        precipitation_brier_count=brier_count,
    )


def _combine_error_summaries(
    summaries: Iterable[ErrorSummary | None],
) -> ErrorSummary | None:
    values = [
        summary
        for summary in summaries
        if summary is not None and summary.count > 0
    ]
    count = sum(summary.count for summary in values)
    if count == 0:
        return None

    return ErrorSummary(
        count=count,
        mae=sum(summary.mae * summary.count for summary in values) / count,
        bias=sum(summary.bias * summary.count for summary in values) / count,
        rmse=math.sqrt(
            sum(
                summary.rmse * summary.rmse * summary.count
                for summary in values
            )
            / count
        ),
    )


def _weighted_mean(
    values: Iterable[tuple[float | None, int]],
) -> float | None:
    usable = [
        (value, count)
        for value, count in values
        if value is not None and count > 0
    ]
    count = sum(item_count for _, item_count in usable)
    if count == 0:
        return None
    return sum(
        float(value) * item_count
        for value, item_count in usable
    ) / count


def _error_summary_from_dict(value: Any) -> ErrorSummary | None:
    if value is None:
        return None
    if not isinstance(value, Mapping):
        raise ValueError("Error summary must be an object")
    return ErrorSummary(
        count=int(value["count"]),
        mae=float(value["mae"]),
        bias=float(value["bias"]),
        rmse=float(value["rmse"]),
    )


def _optional_float(value: Any) -> float | None:
    if value is None:
        return None
    return float(value)
