from __future__ import annotations

from collections import Counter, defaultdict
from datetime import datetime, timezone
import math
from typing import Any, Mapping, Sequence

from .aggregate import aggregate_score_payloads
from .benchmark import score_forecasts
from .model import Forecast
from .observations import ObservationSeries


PRIMARY_METRICS = (
    "temperature_mae",
    "pressure_mae",
    "wind_vector_error_mps",
)
LEAD_BUCKET_ORDER = {
    "0-6h": 0,
    "6-24h": 1,
    "24-48h": 2,
    "48-72h": 3,
}


def analyze_forecast_stability(
    forecasts: Sequence[Forecast],
    observations_by_location: Mapping[str, ObservationSeries],
    *,
    tolerance_minutes: int = 30,
) -> dict[str, object]:
    if not forecasts:
        raise ValueError("stability analysis requires forecasts")
    if tolerance_minutes < 0:
        raise ValueError("tolerance_minutes must not be negative")

    location_ids = sorted({forecast.location.id for forecast in forecasts})
    missing = [
        location_id
        for location_id in location_ids
        if location_id not in observations_by_location
    ]
    if missing:
        raise ValueError(
            "Missing observations for locations: " + ", ".join(missing)
        )

    split_forecasts = _split_forecasts(forecasts)
    aggregate_payloads: dict[str, dict[str, object]] = {}
    full_location_scores: list[dict[str, object]] = []

    for split_name, split_values in split_forecasts.items():
        location_payloads = []
        for location_id in location_ids:
            location_forecasts = [
                forecast
                for forecast in split_values
                if forecast.location.id == location_id
            ]
            scores = score_forecasts(
                location_forecasts,
                observations_by_location[location_id],
                tolerance_minutes=tolerance_minutes,
            )
            location_payloads.append(
                {
                    "location": location_id,
                    "scores": [score.to_dict() for score in scores],
                }
            )

        aggregate = aggregate_score_payloads(location_payloads)
        aggregate_payloads[split_name] = {
            "locations": location_ids,
            "score_file_count": len(location_payloads),
            "scores": [score.to_dict() for score in aggregate],
        }
        if split_name == "all":
            full_location_scores = location_payloads

    summary = analyze_stability(
        aggregate_payloads,
        full_location_scores,
    )
    summary["forecast_counts_by_split"] = {
        name: len(values)
        for name, values in split_forecasts.items()
    }
    summary["location_count"] = len(location_ids)
    summary["aggregates"] = aggregate_payloads
    return summary


def analyze_stability(
    aggregate_payloads: Mapping[str, Mapping[str, Any]],
    location_score_payloads: Sequence[Mapping[str, Any]],
) -> dict[str, object]:
    if not aggregate_payloads:
        raise ValueError("stability analysis requires aggregate payloads")

    pooled: dict[str, object] = {}
    split_bucket_winners: dict[str, object] = {}
    winner_counts = {
        metric: Counter()
        for metric in PRIMARY_METRICS
    }

    for split_name in sorted(aggregate_payloads):
        scores = _score_entries(
            aggregate_payloads[split_name],
            f"aggregate {split_name}",
        )
        pooled[split_name] = _pool_by_model(scores)

        by_bucket: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
        for score in scores:
            by_bucket[str(score["lead_bucket"])].append(score)

        split_winners: dict[str, object] = {}
        for bucket in sorted(
            by_bucket,
            key=lambda value: (LEAD_BUCKET_ORDER.get(value, 999), value),
        ):
            metric_winners: dict[str, object] = {}
            for metric in PRIMARY_METRICS:
                ranked = _rank(by_bucket[bucket], metric)
                if not ranked:
                    continue
                evidence = _winner_evidence(ranked)
                metric_winners[metric] = evidence
                winner = evidence["winner"]
                if isinstance(winner, str):
                    winner_counts[metric][winner] += 1
            split_winners[bucket] = metric_winners
        split_bucket_winners[split_name] = split_winners

    seen_locations: set[str] = set()
    location_wins = {
        metric: Counter()
        for metric in PRIMARY_METRICS
    }
    location_cells = {
        metric: 0
        for metric in PRIMARY_METRICS
    }
    location_ties = {
        metric: 0
        for metric in PRIMARY_METRICS
    }

    for payload in location_score_payloads:
        location = str(payload.get("location") or "").strip()
        if not location:
            raise ValueError("Location score payload has no location")
        if location in seen_locations:
            raise ValueError(f"Duplicate location score payload: {location}")
        seen_locations.add(location)

        by_bucket: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
        for score in _score_entries(payload, f"location {location}"):
            by_bucket[str(score["lead_bucket"])].append(score)

        for bucket_scores in by_bucket.values():
            for metric in PRIMARY_METRICS:
                ranked = _rank(bucket_scores, metric)
                if not ranked:
                    continue
                evidence = _winner_evidence(ranked)
                location_cells[metric] += 1
                winner = evidence["winner"]
                if isinstance(winner, str):
                    location_wins[metric][winner] += 1
                else:
                    location_ties[metric] += 1

    return {
        "primary_metrics": list(PRIMARY_METRICS),
        "pooled": pooled,
        "split_bucket_winners": split_bucket_winners,
        "winner_counts_across_splits_and_leads": {
            metric: dict(sorted(counter.items()))
            for metric, counter in winner_counts.items()
        },
        "location_lead_winners": {
            metric: {
                "cells": location_cells[metric],
                "ties": location_ties[metric],
                "wins": dict(sorted(location_wins[metric].items())),
            }
            for metric in PRIMARY_METRICS
        },
    }


def _split_forecasts(
    forecasts: Sequence[Forecast],
) -> dict[str, tuple[Forecast, ...]]:
    splits: dict[str, list[Forecast]] = {
        "all": [],
        "first-half": [],
        "second-half": [],
        "odd": [],
        "even": [],
    }
    for forecast in forecasts:
        run = forecast.origin.model_run
        if run is None:
            raise ValueError(
                f"Forecast {forecast.origin.model_id} has no model_run"
            )
        day = _parse_utc(run).day
        splits["all"].append(forecast)
        splits["first-half" if day <= 14 else "second-half"].append(forecast)
        splits["odd" if day % 2 else "even"].append(forecast)

    empty = [name for name, values in splits.items() if not values]
    if empty:
        raise ValueError(
            "Stability split has no forecasts: " + ", ".join(empty)
        )
    return {
        name: tuple(values)
        for name, values in splits.items()
    }


def _pool_by_model(
    scores: Sequence[Mapping[str, Any]],
) -> dict[str, object]:
    by_model: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for score in scores:
        by_model[str(score["model_family"])].append(score)

    pooled: dict[str, object] = {}
    for model_family in sorted(by_model):
        metrics: dict[str, object] = {}
        for metric in PRIMARY_METRICS:
            count = sum(
                _metric_count(score, metric)
                for score in by_model[model_family]
            )
            weighted = sum(
                float(value) * _metric_count(score, metric)
                for score in by_model[model_family]
                if (value := _metric_value(score, metric)) is not None
            )
            metrics[metric] = {
                "value": None if count == 0 else weighted / count,
                "count": count,
            }
        pooled[model_family] = metrics
    return pooled


def _rank(
    scores: Sequence[Mapping[str, Any]],
    metric: str,
) -> list[tuple[float, str, int]]:
    ranked = []
    for score in scores:
        value = _metric_value(score, metric)
        count = _metric_count(score, metric)
        if value is None or count <= 0:
            continue
        ranked.append(
            (
                float(value),
                str(score["model_family"]),
                count,
            )
        )
    return sorted(ranked)


def _winner_evidence(
    ranked: Sequence[tuple[float, str, int]],
) -> dict[str, object]:
    best_value = ranked[0][0]
    tied = [
        item
        for item in ranked
        if math.isclose(
            item[0],
            best_value,
            rel_tol=1e-12,
            abs_tol=1e-12,
        )
    ]
    tied_models = sorted(item[1] for item in tied)
    winner = tied_models[0] if len(tied_models) == 1 else None

    runner_up = next(
        (
            value
            for value, _, _ in ranked
            if not math.isclose(
                value,
                best_value,
                rel_tol=1e-12,
                abs_tol=1e-12,
            )
        ),
        None,
    )
    relative_margin = (
        None
        if runner_up in (None, 0.0)
        else (runner_up - best_value) / runner_up
    )
    counts = {
        model: count
        for _, model, count in tied
    }
    return {
        "winner": winner,
        "tied_models": tied_models,
        "best": best_value,
        "runner_up": runner_up,
        "relative_margin": relative_margin,
        "counts": counts,
    }


def _metric_value(
    score: Mapping[str, Any],
    metric: str,
) -> float | None:
    if metric == "wind_vector_error_mps":
        value = score.get(metric)
        return None if value is None else float(value)

    summary_name = (
        "temperature"
        if metric == "temperature_mae"
        else "pressure"
    )
    summary = score.get(summary_name)
    if not isinstance(summary, Mapping):
        return None
    value = summary.get("mae")
    return None if value is None else float(value)


def _metric_count(
    score: Mapping[str, Any],
    metric: str,
) -> int:
    if metric == "wind_vector_error_mps":
        return int(score.get("wind_count") or 0)

    summary_name = (
        "temperature"
        if metric == "temperature_mae"
        else "pressure"
    )
    summary = score.get(summary_name)
    if not isinstance(summary, Mapping):
        return 0
    return int(summary.get("count") or 0)


def _score_entries(
    payload: Mapping[str, Any],
    label: str,
) -> tuple[Mapping[str, Any], ...]:
    raw_scores = payload.get("scores")
    if not isinstance(raw_scores, list):
        raise ValueError(f"{label} has no scores array")
    if not all(isinstance(score, Mapping) for score in raw_scores):
        raise ValueError(f"{label} contains an invalid score")
    return tuple(raw_scores)


def _parse_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)
