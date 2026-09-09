from __future__ import annotations

from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from typing import Any


@dataclass(frozen=True)
class Location:
    id: str
    name: str
    latitude: float
    longitude: float
    timezone_id: str
    climate_note: str


@dataclass(frozen=True)
class Origin:
    provider: str
    model_family: str
    model_id: str
    generated_at: str | None = None
    model_run: str | None = None


@dataclass(frozen=True)
class HourlyPoint:
    time: str
    temperature_c: float | None = None
    feels_like_c: float | None = None
    dew_point_c: float | None = None
    humidity_percent: float | None = None
    pressure_sea_level_hpa: float | None = None
    wind_speed_mps: float | None = None
    wind_gust_mps: float | None = None
    wind_direction_degrees: float | None = None
    precipitation_mm: float | None = None
    precipitation_probability_percent: float | None = None
    cloud_cover_percent: float | None = None
    visibility_meters: float | None = None


@dataclass(frozen=True)
class Forecast:
    origin: Origin
    location: Location
    hourly: tuple[HourlyPoint, ...]

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def utc_iso_from_epoch(value: int | float) -> str:
    return (
        datetime.fromtimestamp(value, tz=timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def normalize_iso_utc(value: str) -> str:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return (
        parsed.astimezone(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )
