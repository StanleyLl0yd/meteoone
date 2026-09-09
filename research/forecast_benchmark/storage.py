from __future__ import annotations

import json
from datetime import date
from pathlib import Path
from typing import Any, Iterable, Mapping

from .model import Forecast, HourlyPoint, Location, Origin
from .observations import (
    IsdStation,
    ObservationSeries,
    ObservedPoint,
    ObservedPrecipitationInterval,
    Wis2Station,
)


def read_forecasts(path: Path) -> tuple[Forecast, ...]:
    forecasts = []
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        if not line.strip():
            continue
        try:
            payload = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(
                f"Invalid JSON in {path} at line {line_number}"
            ) from error
        if not isinstance(payload, Mapping):
            raise ValueError(
                f"Forecast JSON line {line_number} must be an object"
            )
        forecasts.append(forecast_from_dict(payload))
    return tuple(forecasts)


def write_forecasts(path: Path, forecasts: Iterable[Forecast]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        json.dumps(forecast.to_dict(), ensure_ascii=False, sort_keys=True)
        for forecast in forecasts
    ]
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def forecast_from_dict(payload: Mapping[str, Any]) -> Forecast:
    location = payload.get("location")
    origin = payload.get("origin")
    hourly = payload.get("hourly")
    if not isinstance(location, Mapping):
        raise ValueError("Forecast has no location object")
    if not isinstance(origin, Mapping):
        raise ValueError("Forecast has no origin object")
    if not isinstance(hourly, list):
        raise ValueError("Forecast has no hourly array")

    return Forecast(
        origin=Origin(**dict(origin)),
        location=Location(**dict(location)),
        hourly=tuple(HourlyPoint(**dict(point)) for point in hourly),
    )


def read_observations(path: Path) -> ObservationSeries:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, Mapping):
        raise ValueError("Observation file must contain one JSON object")

    station = payload.get("station")
    points = payload.get("points")
    if not isinstance(station, Mapping):
        raise ValueError("Observation file has no station object")
    if not isinstance(points, list):
        raise ValueError("Observation file has no points array")

    station_values = dict(station)
    station_type = station_values.pop("station_type", None)
    station_values["begin"] = date.fromisoformat(str(station_values["begin"]))
    if station_type == "WIS2_WIGOS":
        station_value = Wis2Station(**station_values)
    else:
        station_values["end"] = date.fromisoformat(str(station_values["end"]))
        station_value = IsdStation(**station_values)

    precipitation_intervals = payload.get("precipitation_intervals") or []
    if not isinstance(precipitation_intervals, list):
        raise ValueError(
            "Observation file precipitation_intervals must be an array"
        )

    return ObservationSeries(
        source=str(payload.get("source") or "NOAA_NCEI_ISD"),
        station=station_value,
        points=tuple(ObservedPoint(**dict(point)) for point in points),
        precipitation_intervals=tuple(
            ObservedPrecipitationInterval(**dict(interval))
            for interval in precipitation_intervals
        ),
    )


def write_observations(path: Path, observations: ObservationSeries) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            observations.to_dict(),
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
