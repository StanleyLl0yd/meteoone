from __future__ import annotations

import csv
import io
import math
import urllib.request
from dataclasses import asdict, dataclass
from datetime import date, datetime
from typing import Iterable

from .model import Location, normalize_iso_utc


USER_AGENT = "MeteoOneResearch/0.1 (+https://github.com/StanleyLl0yd/meteoone)"
ISD_HISTORY_URL = "https://www.ncei.noaa.gov/pub/data/noaa/isd-history.csv"
GLOBAL_HOURLY_BASE_URL = "https://www.ncei.noaa.gov/data/global-hourly/access"

# NCEI ISD quality flags accepted for benchmark observations:
# passed gross-limit/all checks, including NCEI-origin variants.
ACCEPTED_QUALITY_CODES = frozenset({"0", "1", "4", "5", "9"})


@dataclass(frozen=True)
class IsdStation:
    station_id: str
    usaf: str
    wban: str
    name: str
    country: str
    icao: str | None
    latitude: float
    longitude: float
    elevation_m: float | None
    begin: date
    end: date

    def to_dict(self) -> dict[str, object]:
        value = asdict(self)
        value["begin"] = self.begin.isoformat()
        value["end"] = self.end.isoformat()
        return value


@dataclass(frozen=True)
class StationMatch:
    station: IsdStation
    distance_km: float
    elevation_delta_m: float | None


@dataclass(frozen=True)
class ObservedPoint:
    time: str
    temperature_c: float | None = None
    pressure_sea_level_hpa: float | None = None
    wind_speed_mps: float | None = None
    wind_direction_degrees: float | None = None
    precipitation_mm: float | None = None
    precipitation_trace: bool | None = None


@dataclass(frozen=True)
class ObservationSeries:
    source: str
    station: IsdStation
    points: tuple[ObservedPoint, ...]

    def to_dict(self) -> dict[str, object]:
        return {
            "source": self.source,
            "station": self.station.to_dict(),
            "points": [asdict(point) for point in self.points],
        }


class TextHttpClient:
    def __init__(self, timeout_seconds: float = 30.0) -> None:
        self.timeout_seconds = timeout_seconds

    def get_text(self, url: str) -> str:
        request = urllib.request.Request(
            url,
            headers={
                "Accept": "text/csv,text/plain;q=0.9,*/*;q=0.1",
                "User-Agent": USER_AGENT,
            },
            method="GET",
        )
        with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
            if response.status != 200:
                raise RuntimeError(f"HTTP {response.status} for {url}")
            return response.read().decode("utf-8-sig")


class NceiIsdAdapter:
    def __init__(self, http: TextHttpClient | None = None) -> None:
        self.http = http or TextHttpClient()

    def fetch_station_index(self) -> tuple[IsdStation, ...]:
        return parse_station_history(self.http.get_text(ISD_HISTORY_URL))

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
        return select_station(
            location,
            self.fetch_station_index(),
            start,
            end,
            max_distance_km=max_distance_km,
            target_elevation_m=target_elevation_m,
            max_elevation_delta_m=max_elevation_delta_m,
        )

    def fetch_year(self, station: IsdStation, year: int) -> ObservationSeries:
        url = f"{GLOBAL_HOURLY_BASE_URL}/{year}/{station.station_id}.csv"
        points = parse_global_hourly(self.http.get_text(url), station.station_id)
        return ObservationSeries(source="NOAA_NCEI_ISD", station=station, points=points)


def parse_station_history(text: str) -> tuple[IsdStation, ...]:
    stations: list[IsdStation] = []
    for row in csv.DictReader(io.StringIO(text)):
        usaf = (row.get("USAF") or "").strip()
        wban = (row.get("WBAN") or "").strip()
        lat = _optional_float(row.get("LAT"))
        lon = _optional_float(row.get("LON"))
        begin = _parse_yyyymmdd(row.get("BEGIN"))
        end = _parse_yyyymmdd(row.get("END"))
        if not usaf or not wban or lat is None or lon is None or begin is None or end is None:
            continue
        stations.append(
            IsdStation(
                station_id=f"{usaf}{wban}",
                usaf=usaf,
                wban=wban,
                name=(row.get("STATION NAME") or "").strip(),
                country=(row.get("CTRY") or "").strip(),
                icao=(row.get("ICAO") or "").strip() or None,
                latitude=lat,
                longitude=lon,
                elevation_m=_optional_float(row.get("ELEV(M)")),
                begin=begin,
                end=end,
            )
        )
    return tuple(stations)


def select_station(
    location: Location,
    stations: Iterable[IsdStation],
    start: date,
    end: date,
    *,
    max_distance_km: float = 75.0,
    target_elevation_m: float | None = None,
    max_elevation_delta_m: float = 300.0,
) -> StationMatch:
    if start > end:
        raise ValueError("start must not be after end")
    if max_distance_km <= 0:
        raise ValueError("max_distance_km must be positive")
    if max_elevation_delta_m < 0:
        raise ValueError("max_elevation_delta_m must not be negative")

    matches: list[StationMatch] = []
    for station in stations:
        if station.begin > start or station.end < end:
            continue

        distance = haversine_km(
            location.latitude,
            location.longitude,
            station.latitude,
            station.longitude,
        )
        if distance > max_distance_km:
            continue

        elevation_delta = None
        if target_elevation_m is not None:
            if station.elevation_m is None:
                continue
            elevation_delta = abs(station.elevation_m - target_elevation_m)
            if elevation_delta > max_elevation_delta_m:
                continue

        matches.append(
            StationMatch(
                station=station,
                distance_km=distance,
                elevation_delta_m=elevation_delta,
            )
        )

    if not matches:
        raise LookupError(
            f"No ISD station covers {location.id} within {max_distance_km:g} km "
            f"for {start.isoformat()}..{end.isoformat()}"
        )

    return min(
        matches,
        key=lambda match: (
            match.distance_km,
            match.elevation_delta_m
            if match.elevation_delta_m is not None
            else float("inf"),
            match.station.station_id,
        ),
    )


def parse_global_hourly(text: str, station_id: str) -> tuple[ObservedPoint, ...]:
    best_by_time: dict[str, ObservedPoint] = {}
    for row in csv.DictReader(io.StringIO(text)):
        row_station = (row.get("STATION") or "").strip()
        if row_station and row_station != station_id:
            continue

        raw_time = (row.get("DATE") or "").strip()
        if not raw_time:
            continue
        time = normalize_iso_utc(raw_time)

        wind_speed, wind_direction = _parse_wind(row.get("WND"))
        precipitation_mm, precipitation_trace = _parse_hourly_precipitation(row)

        point = ObservedPoint(
            time=time,
            temperature_c=_parse_scaled(row.get("TMP"), missing="+9999"),
            pressure_sea_level_hpa=_parse_scaled(row.get("SLP"), missing="99999"),
            wind_speed_mps=wind_speed,
            wind_direction_degrees=wind_direction,
            precipitation_mm=precipitation_mm,
            precipitation_trace=precipitation_trace,
        )
        previous = best_by_time.get(time)
        if previous is None or _completeness(point) > _completeness(previous):
            best_by_time[time] = point

    return tuple(best_by_time[key] for key in sorted(best_by_time))


def _parse_scaled(raw: str | None, *, missing: str) -> float | None:
    if not raw:
        return None
    parts = [part.strip() for part in raw.split(",")]
    if len(parts) < 2 or parts[0] == missing or parts[1] not in ACCEPTED_QUALITY_CODES:
        return None
    try:
        return int(parts[0]) / 10.0
    except ValueError:
        return None


def _parse_wind(raw: str | None) -> tuple[float | None, float | None]:
    if not raw:
        return None, None
    parts = [part.strip() for part in raw.split(",")]
    if len(parts) < 5:
        return None, None

    direction = None
    if parts[0] != "999" and parts[1] in ACCEPTED_QUALITY_CODES:
        try:
            direction = float(int(parts[0]) % 360)
        except ValueError:
            pass

    speed = None
    if parts[3] != "9999" and parts[4] in ACCEPTED_QUALITY_CODES:
        try:
            speed = int(parts[3]) / 10.0
        except ValueError:
            pass

    if speed == 0.0 and direction is None:
        direction = 0.0
    return speed, direction


def _parse_hourly_precipitation(
    row: dict[str, str],
) -> tuple[float | None, bool | None]:
    for key in ("AA1", "AA2", "AA3", "AA4"):
        raw = row.get(key)
        if not raw:
            continue
        parts = [part.strip() for part in raw.split(",")]
        if len(parts) < 4 or parts[0] != "01":
            continue

        depth, condition, quality = parts[1], parts[2], parts[3]
        if depth == "9999" or quality not in ACCEPTED_QUALITY_CODES:
            continue
        if condition in {"1", "3", "5"}:
            continue

        try:
            amount = int(depth) / 10.0
        except ValueError:
            continue
        return amount, condition == "2"

    return None, None


def _completeness(point: ObservedPoint) -> int:
    return sum(
        value is not None
        for value in (
            point.temperature_c,
            point.pressure_sea_level_hpa,
            point.wind_speed_mps,
            point.wind_direction_degrees,
            point.precipitation_mm,
        )
    )


def haversine_km(
    lat1: float,
    lon1: float,
    lat2: float,
    lon2: float,
) -> float:
    radius_km = 6371.0088
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lon2 - lon1)
    a = (
        math.sin(d_phi / 2.0) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2.0) ** 2
    )
    return 2.0 * radius_km * math.asin(math.sqrt(a))


def _optional_float(raw: str | None) -> float | None:
    if raw is None:
        return None
    value = raw.strip()
    if not value:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def _parse_yyyymmdd(raw: str | None) -> date | None:
    if raw is None:
        return None
    value = raw.strip()
    if len(value) != 8 or not value.isdigit():
        return None
    try:
        return datetime.strptime(value, "%Y%m%d").date()
    except ValueError:
        return None
