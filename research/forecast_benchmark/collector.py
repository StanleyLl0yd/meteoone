from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import date, datetime, time, timedelta, timezone
from typing import Iterable, Protocol

from .model import Forecast, Location
from .providers import OPEN_METEO_MODELS, OpenMeteoModel


@dataclass(frozen=True)
class CollectionError:
    location_id: str
    model_id: str
    run: str
    error_type: str
    message: str

    def to_dict(self) -> dict[str, str]:
        return asdict(self)


@dataclass(frozen=True)
class CollectionResult:
    forecasts: tuple[Forecast, ...]
    errors: tuple[CollectionError, ...]


class ArchivedRunAdapter(Protocol):
    def fetch_single_run(
        self,
        location: Location,
        model: OpenMeteoModel,
        run: str,
        forecast_hours: int = 72,
    ) -> Forecast:
        ...


def iter_runs(
    start: date,
    end: date,
    *,
    cycle_hours: tuple[int, ...] = (0, 6, 12, 18),
) -> tuple[str, ...]:
    if start > end:
        raise ValueError("start must not be after end")
    if not cycle_hours:
        raise ValueError("cycle_hours must not be empty")
    if any(hour < 0 or hour > 23 for hour in cycle_hours):
        raise ValueError("cycle hour must be within 0..23")
    if len(set(cycle_hours)) != len(cycle_hours):
        raise ValueError("cycle_hours must not contain duplicates")

    runs = []
    current = start
    while current <= end:
        for hour in sorted(cycle_hours):
            value = datetime.combine(
                current,
                time(hour=hour),
                tzinfo=timezone.utc,
            )
            runs.append(value.strftime("%Y-%m-%dT%H:%MZ"))
        current += timedelta(days=1)
    return tuple(runs)


def collect_archived_runs(
    adapter: ArchivedRunAdapter,
    locations: Iterable[Location],
    runs: Iterable[str],
    *,
    models: Iterable[OpenMeteoModel] = OPEN_METEO_MODELS,
    forecast_hours: int = 72,
) -> CollectionResult:
    if forecast_hours < 1 or forecast_hours > 384:
        raise ValueError("forecast_hours must be between 1 and 384")

    location_values = tuple(locations)
    model_values = tuple(models)
    run_values = tuple(runs)
    forecasts: list[Forecast] = []
    errors: list[CollectionError] = []

    for location in location_values:
        for run in run_values:
            for model in model_values:
                try:
                    forecasts.append(
                        adapter.fetch_single_run(
                            location,
                            model,
                            run,
                            forecast_hours,
                        )
                    )
                except Exception as error:
                    errors.append(
                        CollectionError(
                            location_id=location.id,
                            model_id=model.model_id,
                            run=run,
                            error_type=type(error).__name__,
                            message=str(error),
                        )
                    )

    return CollectionResult(
        forecasts=tuple(forecasts),
        errors=tuple(errors),
    )
