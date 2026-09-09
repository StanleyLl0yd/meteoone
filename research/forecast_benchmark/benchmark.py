from __future__ import annotations

import bisect
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone

from .metrics import (
    ErrorSummary,
    brier_score,
    error_summary,
    lead_bucket,
    wind_vector_error_mps,
)
from .model import Forecast, HourlyPoint
from .observations import (
    ObservationSeries,
    ObservedPoint,
    ObservedPrecipitationInterval,
)


@dataclass(frozen=True)
class BucketScore:
    model_family: str
    lead_bucket: str
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


class _Accumulator:
    def __init__(self) -> None:
        self.matched_points = 0
        self.temperature_predicted: list[float | None] = []
        self.temperature_observed: list[float | None] = []
        self.pressure_predicted: list[float | None] = []
        self.pressure_observed: list[float | None] = []
        self.precipitation_predicted: list[float | None] = []
        self.precipitation_observed: list[float | None] = []
        self.wind_speed_predicted: list[float | None] = []
        self.wind_direction_predicted: list[float | None] = []
        self.wind_speed_observed: list[float | None] = []
        self.wind_direction_observed: list[float | None] = []
        self.precipitation_probability: list[float | None] = []
        self.precipitation_event: list[bool | None] = []


def score_forecasts(
    forecasts: list[Forecast] | tuple[Forecast, ...],
    observations: ObservationSeries,
    *,
    tolerance_minutes: int = 30,
) -> tuple[BucketScore, ...]:
    if tolerance_minutes < 0:
        raise ValueError("tolerance_minutes must not be negative")

    location_ids = {forecast.location.id for forecast in forecasts}
    if len(location_ids) > 1:
        raise ValueError("score_forecasts requires one benchmark location")

    observation_pairs = sorted(
        ((_parse_utc(point.time), point) for point in observations.points),
        key=lambda pair: pair[0],
    )
    observation_times = [pair[0] for pair in observation_pairs]
    observation_points = tuple(pair[1] for pair in observation_pairs)
    groups: dict[tuple[str, str], _Accumulator] = {}
    use_interval_precipitation = bool(observations.precipitation_intervals)

    for forecast in forecasts:
        if forecast.origin.model_run is None:
            raise ValueError(
                f"Forecast {forecast.origin.model_id} has no model_run"
            )
        run_time = _parse_utc(forecast.origin.model_run)

        for point in forecast.hourly:
            valid_time = _parse_utc(point.time)
            bucket = lead_bucket((valid_time - run_time).total_seconds() / 3600.0)
            if bucket is None:
                continue

            observed = _nearest_observation(
                valid_time,
                observation_points,
                observation_times,
                tolerance_minutes=tolerance_minutes,
            )
            if observed is None:
                continue

            key = (forecast.origin.model_family, bucket)
            accumulator = groups.setdefault(key, _Accumulator())
            _accumulate(
                accumulator,
                point,
                observed,
                include_precipitation=not use_interval_precipitation,
            )

        _accumulate_interval_precipitation(
            groups,
            forecast,
            observations.precipitation_intervals,
            run_time,
        )

    bucket_order = {"0-6h": 0, "6-24h": 1, "24-48h": 2, "48-72h": 3}
    keys = sorted(groups, key=lambda key: (key[0], bucket_order[key[1]]))
    return tuple(
        _finish(model_family, bucket, groups[(model_family, bucket)])
        for model_family, bucket in keys
    )


def _nearest_observation(
    valid_time: datetime,
    points: tuple[ObservedPoint, ...],
    times: list[datetime],
    *,
    tolerance_minutes: int,
) -> ObservedPoint | None:
    if not times:
        return None

    index = bisect.bisect_left(times, valid_time)
    candidates = []
    if index < len(times):
        candidates.append(index)
    if index > 0:
        candidates.append(index - 1)

    best = min(
        candidates,
        key=lambda candidate: (
            abs((times[candidate] - valid_time).total_seconds()),
            times[candidate],
        ),
    )
    delta_seconds = abs((times[best] - valid_time).total_seconds())
    if delta_seconds > tolerance_minutes * 60:
        return None
    return points[best]


def _accumulate(
    accumulator: _Accumulator,
    predicted: HourlyPoint,
    observed: ObservedPoint,
    *,
    include_precipitation: bool,
) -> None:
    accumulator.matched_points += 1

    accumulator.temperature_predicted.append(predicted.temperature_c)
    accumulator.temperature_observed.append(observed.temperature_c)
    accumulator.pressure_predicted.append(predicted.pressure_sea_level_hpa)
    accumulator.pressure_observed.append(observed.pressure_sea_level_hpa)
    if include_precipitation:
        accumulator.precipitation_predicted.append(predicted.precipitation_mm)
        accumulator.precipitation_observed.append(observed.precipitation_mm)

    accumulator.wind_speed_predicted.append(predicted.wind_speed_mps)
    accumulator.wind_direction_predicted.append(predicted.wind_direction_degrees)
    accumulator.wind_speed_observed.append(observed.wind_speed_mps)
    accumulator.wind_direction_observed.append(observed.wind_direction_degrees)

    accumulator.precipitation_probability.append(
        predicted.precipitation_probability_percent
    )
    accumulator.precipitation_event.append(_precipitation_event(observed))



def _accumulate_interval_precipitation(
    groups: dict[tuple[str, str], _Accumulator],
    forecast: Forecast,
    intervals: tuple[ObservedPrecipitationInterval, ...],
    run_time: datetime,
) -> None:
    if not intervals:
        return

    # The M0 archived Open-Meteo adapter has a verified temporal contract:
    # each hourly precipitation value is the sum of the preceding hour.
    # Other providers must expose an equally explicit period contract before
    # they can be compared with SYNOP accumulation intervals.
    if forecast.origin.provider != "OPEN_METEO":
        return

    precipitation_by_end = {
        _parse_utc(point.time): point.precipitation_mm
        for point in forecast.hourly
    }
    for interval in intervals:
        start = _parse_utc(interval.start_time)
        end = _parse_utc(interval.end_time)
        if start < run_time:
            continue

        bucket = lead_bucket((end - run_time).total_seconds() / 3600.0)
        if bucket is None:
            continue

        predicted = _sum_preceding_hour_precipitation(
            precipitation_by_end,
            start,
            end,
        )
        if predicted is None:
            continue

        accumulator = groups.setdefault(
            (forecast.origin.model_family, bucket),
            _Accumulator(),
        )
        accumulator.precipitation_predicted.append(predicted)
        accumulator.precipitation_observed.append(interval.amount_mm)


def _sum_preceding_hour_precipitation(
    precipitation_by_end: dict[datetime, float | None],
    start: datetime,
    end: datetime,
) -> float | None:
    duration_seconds = (end - start).total_seconds()
    if duration_seconds <= 0 or duration_seconds % 3600 != 0:
        return None
    if any(
        value != 0
        for value in (
            start.minute,
            start.second,
            start.microsecond,
            end.minute,
            end.second,
            end.microsecond,
        )
    ):
        return None

    values = []
    for offset in range(1, int(duration_seconds // 3600) + 1):
        value = precipitation_by_end.get(start + timedelta(hours=offset))
        if value is None:
            return None
        values.append(value)
    return sum(values)


def _finish(
    model_family: str,
    bucket: str,
    accumulator: _Accumulator,
) -> BucketScore:
    wind_count = sum(
        None not in values
        for values in zip(
            accumulator.wind_speed_predicted,
            accumulator.wind_direction_predicted,
            accumulator.wind_speed_observed,
            accumulator.wind_direction_observed,
            strict=True,
        )
    )
    brier_count = sum(
        probability is not None and event is not None
        for probability, event in zip(
            accumulator.precipitation_probability,
            accumulator.precipitation_event,
            strict=True,
        )
    )

    return BucketScore(
        model_family=model_family,
        lead_bucket=bucket,
        matched_points=accumulator.matched_points,
        temperature=error_summary(
            accumulator.temperature_predicted,
            accumulator.temperature_observed,
        ),
        pressure=error_summary(
            accumulator.pressure_predicted,
            accumulator.pressure_observed,
        ),
        precipitation=error_summary(
            accumulator.precipitation_predicted,
            accumulator.precipitation_observed,
        ),
        wind_vector_error_mps=wind_vector_error_mps(
            accumulator.wind_speed_predicted,
            accumulator.wind_direction_predicted,
            accumulator.wind_speed_observed,
            accumulator.wind_direction_observed,
        ),
        wind_count=wind_count,
        precipitation_brier=brier_score(
            accumulator.precipitation_probability,
            accumulator.precipitation_event,
        ),
        precipitation_brier_count=brier_count,
    )


def _precipitation_event(point: ObservedPoint) -> bool | None:
    if point.precipitation_trace is True:
        return True
    if point.precipitation_mm is None:
        return None
    return point.precipitation_mm > 0.0


def _parse_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)
