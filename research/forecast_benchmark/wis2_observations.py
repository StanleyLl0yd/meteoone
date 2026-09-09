from __future__ import annotations

import json
import math
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Mapping

from .model import Location, normalize_iso_utc
from .observations import (
    ObservationSeries,
    ObservedPoint,
    ObservedPrecipitationInterval,
    StationMatch,
    Wis2Station,
    haversine_km,
)


USER_AGENT = "MeteoOneResearch/0.1 (+https://github.com/StanleyLl0yd/meteoone)"
SOURCE_ID = "ROSHYDROMET_WIS2_SYNOP"
DATASET_ID = "urn:wmo:md:ru-roshydromet:core.surface-based-observations.synop"
WIS2_ITEMS_URL = (
    "https://wis2box.mecom.ru/oapi/collections/"
    f"{DATASET_ID}/items"
)
DEFAULT_STATIONS = Path(__file__).with_name("wis2_stations.json")
RETRYABLE_HTTP_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})


@dataclass(frozen=True)
class RawJsonResponse:
    body: bytes
    payload: Mapping[str, Any]
    final_url: str


@dataclass(frozen=True)
class Wis2ObservationFetch:
    series: ObservationSeries
    raw_body: bytes
    request_url: str
    matched_features: int
    returned_features: int


class Wis2HttpClient:
    def __init__(
        self,
        timeout_seconds: float = 60.0,
        *,
        max_attempts: int = 3,
        backoff_seconds: float = 1.0,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        if max_attempts < 1:
            raise ValueError("max_attempts must be positive")
        if backoff_seconds < 0:
            raise ValueError("backoff_seconds must not be negative")
        self.timeout_seconds = timeout_seconds
        self.max_attempts = max_attempts
        self.backoff_seconds = backoff_seconds
        self.sleep = sleep

    def get_json(
        self,
        url: str,
        params: Mapping[str, str],
    ) -> RawJsonResponse:
        query = urllib.parse.urlencode(params, safe=",")
        request_url = f"{url}?{query}"
        request = urllib.request.Request(
            request_url,
            headers={
                "Accept": "application/geo+json,application/json",
                "User-Agent": USER_AGENT,
            },
            method="GET",
        )

        last_error: BaseException | None = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                with urllib.request.urlopen(
                    request,
                    timeout=self.timeout_seconds,
                ) as response:
                    body = response.read()
                    if response.status != 200:
                        raise RuntimeError(
                            f"HTTP {response.status} for {request_url}"
                        )
                    payload = json.loads(body)
                    if not isinstance(payload, Mapping):
                        raise ValueError(
                            "WIS2 response must contain a JSON object"
                        )
                    return RawJsonResponse(
                        body=body,
                        payload=payload,
                        final_url=response.geturl(),
                    )
            except urllib.error.HTTPError as error:
                detail = error.read(1024).decode(
                    "utf-8",
                    errors="replace",
                ).strip()
                last_error = RuntimeError(
                    f"HTTP {error.code} for {request_url}"
                    + (f": {detail}" if detail else "")
                )
                if (
                    error.code not in RETRYABLE_HTTP_STATUS
                    or attempt == self.max_attempts
                ):
                    raise last_error from error
            except (
                urllib.error.URLError,
                TimeoutError,
                ssl.SSLError,
            ) as error:
                last_error = error
                if attempt == self.max_attempts:
                    raise RuntimeError(
                        f"Network failure for {request_url}: {error}"
                    ) from error

            if self.backoff_seconds:
                self.sleep(self.backoff_seconds * (2 ** (attempt - 1)))

        raise RuntimeError(
            f"Failed to fetch {request_url}: {last_error}"
        )


class RoshydrometWis2Adapter:
    def __init__(
        self,
        http: Wis2HttpClient | None = None,
        *,
        stations_path: Path = DEFAULT_STATIONS,
    ) -> None:
        self.http = http or Wis2HttpClient()
        self.stations = load_wis2_stations(stations_path)

    def find_station(
        self,
        location: Location,
        start: date,
        end: date,
        *,
        max_distance_km: float = 75.0,
        target_elevation_m: float | None = None,
        max_elevation_delta_m: float = 300.0,
    ) -> StationMatch:
        station = self.stations.get(location.id)
        if station is None:
            raise LookupError(
                f"No verified WIS2 station configured for {location.id}"
            )
        return select_wis2_station(
            location,
            station,
            start,
            end,
            max_distance_km=max_distance_km,
            target_elevation_m=target_elevation_m,
            max_elevation_delta_m=max_elevation_delta_m,
        )

    def fetch_period(
        self,
        station: Wis2Station,
        start: date,
        end: date,
        *,
        min_coverage_ratio: float = 0.98,
    ) -> Wis2ObservationFetch:
        if start > end:
            raise ValueError("start must not be after end")
        if start < station.begin:
            raise ValueError(
                f"WIS2 station {station.station_id} starts at "
                f"{station.begin.isoformat()}"
            )

        response = self.http.get_json(
            WIS2_ITEMS_URL,
            {
                "f": "json",
                "datetime": (
                    f"{start.isoformat()}T00:00:00Z/"
                    f"{end.isoformat()}T23:59:59Z"
                ),
                "wigos_station_identifier": station.station_id,
                "limit": "10000",
            },
        )
        matched = _integer(response.payload.get("numberMatched"))
        returned = _integer(response.payload.get("numberReturned"))
        features = response.payload.get("features")
        if not isinstance(features, list):
            raise ValueError("WIS2 response has no features array")
        if returned != len(features):
            raise ValueError(
                "WIS2 numberReturned does not match feature count"
            )
        if matched > returned:
            raise RuntimeError(
                f"WIS2 response truncated: matched={matched} "
                f"returned={returned}"
            )

        series = parse_roshydromet_synop(response.payload, station)
        validate_synop_coverage(
            series,
            start,
            end,
            min_coverage_ratio=min_coverage_ratio,
        )
        return Wis2ObservationFetch(
            series=series,
            raw_body=response.body,
            request_url=response.final_url,
            matched_features=matched,
            returned_features=returned,
        )


def load_wis2_stations(
    path: Path = DEFAULT_STATIONS,
) -> dict[str, Wis2Station]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, list):
        raise ValueError("WIS2 station file must contain an array")

    stations: dict[str, Wis2Station] = {}
    station_ids: set[str] = set()
    for item in raw:
        if not isinstance(item, Mapping):
            raise ValueError("WIS2 station entry must be an object")
        location_id = str(item["location_id"])
        if location_id in stations:
            raise ValueError(
                f"Duplicate WIS2 location id: {location_id}"
            )
        station_id = str(item["station_id"])
        if station_id in station_ids:
            raise ValueError(
                f"Duplicate WIS2 station id: {station_id}"
            )
        station_ids.add(station_id)
        stations[location_id] = Wis2Station(
            station_id=station_id,
            name=str(item["name"]),
            country=str(item["country"]),
            latitude=float(item["latitude"]),
            longitude=float(item["longitude"]),
            elevation_m=_optional_float(item.get("elevation_m")),
            begin=date.fromisoformat(str(item["begin"])),
            dataset_id=str(item["dataset_id"]),
        )
    return stations


def select_wis2_station(
    location: Location,
    station: Wis2Station,
    start: date,
    end: date,
    *,
    max_distance_km: float = 75.0,
    target_elevation_m: float | None = None,
    max_elevation_delta_m: float = 300.0,
) -> StationMatch:
    if start > end:
        raise ValueError("start must not be after end")
    if start < station.begin:
        raise LookupError(
            f"WIS2 station {station.station_id} does not cover "
            f"{start.isoformat()}"
        )
    if max_distance_km <= 0:
        raise ValueError("max_distance_km must be positive")
    if max_elevation_delta_m < 0:
        raise ValueError(
            "max_elevation_delta_m must not be negative"
        )

    distance = haversine_km(
        location.latitude,
        location.longitude,
        station.latitude,
        station.longitude,
    )
    if distance > max_distance_km:
        raise LookupError(
            f"WIS2 station {station.station_id} is "
            f"{distance:.1f} km from {location.id}"
        )

    elevation_delta = None
    if target_elevation_m is not None:
        if station.elevation_m is None:
            raise LookupError(
                f"WIS2 station {station.station_id} has no elevation"
            )
        elevation_delta = abs(
            station.elevation_m - target_elevation_m
        )
        if elevation_delta > max_elevation_delta_m:
            raise LookupError(
                f"WIS2 station {station.station_id} elevation delta "
                f"{elevation_delta:.1f} m exceeds "
                f"{max_elevation_delta_m:.1f} m"
            )

    return StationMatch(
        station=station,
        distance_km=distance,
        elevation_delta_m=elevation_delta,
    )


def parse_roshydromet_synop(
    payload: Mapping[str, Any],
    station: Wis2Station,
) -> ObservationSeries:
    features = payload.get("features")
    if not isinstance(features, list):
        raise ValueError("WIS2 response has no features array")

    fields_by_time: dict[
        str,
        dict[str, list[tuple[float, str]]],
    ] = {}
    precipitation: list[ObservedPrecipitationInterval] = []

    for feature in features:
        if not isinstance(feature, Mapping):
            continue
        properties = feature.get("properties")
        if not isinstance(properties, Mapping):
            continue
        station_id = properties.get("wigos_station_identifier")
        if station_id != station.station_id:
            raise ValueError(
                f"Unexpected WIS2 station {station_id!r}; "
                f"expected {station.station_id}"
            )
        report_time_raw = properties.get("reportTime")
        name = properties.get("name")
        if not isinstance(report_time_raw, str) or not isinstance(name, str):
            continue
        report_time = normalize_iso_utc(report_time_raw)

        if name == "total_precipitation_or_total_water_equivalent":
            interval = _parse_precipitation_interval(properties)
            if interval is not None:
                precipitation.append(interval)
            continue

        spec = _FIELD_SPECS.get(name)
        if spec is None:
            continue
        expected_units, minimum, maximum = spec
        if properties.get("units") != expected_units:
            continue
        value = _number(properties.get("value"))
        if value is None or value < minimum or value > maximum:
            continue
        if name == "wind_direction" and value == 360.0:
            value = 0.0

        phenomenon = str(properties.get("phenomenonTime") or "")
        fields_by_time.setdefault(report_time, {}).setdefault(
            name,
            [],
        ).append((value, phenomenon))

    points = []
    for report_time in sorted(fields_by_time):
        values = fields_by_time[report_time]
        points.append(
            ObservedPoint(
                time=report_time,
                temperature_c=_unique_value(
                    values.get("air_temperature")
                ),
                pressure_sea_level_hpa=_unique_value(
                    values.get("pressure_reduced_to_mean_sea_level")
                ),
                wind_speed_mps=_unique_value(
                    values.get("wind_speed")
                ),
                wind_direction_degrees=_unique_value(
                    values.get("wind_direction")
                ),
            )
        )

    unique_precip = {
        (
            value.start_time,
            value.end_time,
            value.amount_mm,
            value.trace,
        ): value
        for value in precipitation
    }
    return ObservationSeries(
        source=SOURCE_ID,
        station=station,
        points=tuple(points),
        precipitation_intervals=tuple(
            unique_precip[key] for key in sorted(unique_precip)
        ),
    )


def validate_synop_coverage(
    series: ObservationSeries,
    start: date,
    end: date,
    *,
    min_coverage_ratio: float = 0.98,
) -> None:
    if not 0.0 < min_coverage_ratio <= 1.0:
        raise ValueError(
            "min_coverage_ratio must be within (0, 1]"
        )
    expected = _expected_synop_times(start, end)
    if not expected:
        raise ValueError("benchmark period must contain SYNOP times")

    by_time = {point.time: point for point in series.points}
    fields = {
        "temperature": lambda p: p.temperature_c,
        "pressure": lambda p: p.pressure_sea_level_hpa,
        "wind_speed": lambda p: p.wind_speed_mps,
        "wind_direction": lambda p: p.wind_direction_degrees,
    }
    failures = []
    for name, selector in fields.items():
        usable = sum(
            time_value in by_time
            and selector(by_time[time_value]) is not None
            for time_value in expected
        )
        ratio = usable / len(expected)
        if ratio < min_coverage_ratio:
            failures.append(
                f"{name}={usable}/{len(expected)} ({ratio:.3f})"
            )

    if failures:
        raise LookupError(
            f"WIS2 coverage below {min_coverage_ratio:.3f}: "
            + ", ".join(failures)
        )


_FIELD_SPECS: dict[str, tuple[str, float, float]] = {
    "air_temperature": ("Celsius", -90.0, 60.0),
    "pressure_reduced_to_mean_sea_level": (
        "hPa",
        800.0,
        1100.0,
    ),
    "wind_speed": ("m/s", 0.0, 100.0),
    "wind_direction": ("deg", 0.0, 360.0),
}


def _unique_value(
    candidates: list[tuple[float, str]] | None,
) -> float | None:
    if not candidates:
        return None
    values = {value for value, _ in candidates}
    if len(values) != 1:
        return None
    return next(iter(values))


def _parse_precipitation_interval(
    properties: Mapping[str, Any],
) -> ObservedPrecipitationInterval | None:
    if properties.get("units") != "kg m-2":
        return None
    value = _number(properties.get("value"))
    if value is None or value < 0.0 or value > 1000.0:
        return None
    phenomenon = properties.get("phenomenonTime")
    if not isinstance(phenomenon, str) or "/" not in phenomenon:
        return None
    raw_start, raw_end = phenomenon.split("/", 1)
    try:
        start = _parse_utc(raw_start)
        end = _parse_utc(raw_end)
    except ValueError:
        return None
    if end <= start:
        return None
    return ObservedPrecipitationInterval(
        start_time=_iso_utc(start),
        end_time=_iso_utc(end),
        amount_mm=value,
    )


def _expected_synop_times(
    start: date,
    end: date,
) -> tuple[str, ...]:
    current = datetime.combine(
        start,
        datetime.min.time(),
        tzinfo=timezone.utc,
    )
    stop = datetime.combine(
        end + timedelta(days=1),
        datetime.min.time(),
        tzinfo=timezone.utc,
    )
    values = []
    while current < stop:
        values.append(_iso_utc(current))
        current += timedelta(hours=3)
    return tuple(values)


def _parse_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(
        value.replace("Z", "+00:00")
    )
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _iso_utc(value: datetime) -> str:
    return (
        value.astimezone(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def _integer(value: Any) -> int:
    if isinstance(value, bool):
        raise ValueError("integer value must not be boolean")
    try:
        result = int(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"Invalid integer value: {value!r}") from error
    if result < 0:
        raise ValueError("integer value must not be negative")
    return result


def _number(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _optional_float(value: Any) -> float | None:
    return _number(value)
