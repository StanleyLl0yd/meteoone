from __future__ import annotations

import json
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

from .model import (
    Forecast,
    HourlyPoint,
    Location,
    Origin,
    normalize_iso_utc,
    utc_iso_from_epoch,
)


USER_AGENT = "MeteoOneResearch/0.1 (+https://github.com/StanleyLl0yd/meteoone)"
OPEN_METEO_LIVE_URL = "https://api.open-meteo.com/v1/forecast"
OPEN_METEO_SINGLE_RUN_URL = "https://single-runs-api.open-meteo.com/v1/forecast"
MET_NORWAY_URL = "https://api.met.no/weatherapi/locationforecast/2.0/compact"

HOURLY_FIELDS = (
    "temperature_2m",
    "apparent_temperature",
    "dew_point_2m",
    "relative_humidity_2m",
    "pressure_msl",
    "wind_speed_10m",
    "wind_gusts_10m",
    "wind_direction_10m",
    "precipitation",
    "precipitation_probability",
    "cloud_cover",
    "visibility",
)


@dataclass(frozen=True)
class OpenMeteoModel:
    model_id: str
    model_family: str


OPEN_METEO_MODELS: tuple[OpenMeteoModel, ...] = (
    OpenMeteoModel("ecmwf_ifs", "ECMWF_IFS"),
    OpenMeteoModel("icon_global", "DWD_ICON"),
    OpenMeteoModel("ncep_gfs_global", "NOAA_GFS"),
)


class JsonHttpClient:
    def __init__(self, timeout_seconds: float = 20.0) -> None:
        self.timeout_seconds = timeout_seconds

    def get(self, url: str, params: Mapping[str, str]) -> dict[str, Any]:
        query = urllib.parse.urlencode(params, safe=",")
        request = urllib.request.Request(
            f"{url}?{query}",
            headers={
                "Accept": "application/json",
                "User-Agent": USER_AGENT,
            },
            method="GET",
        )
        with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
            if response.status != 200:
                raise RuntimeError(f"HTTP {response.status} for {url}")
            return json.load(response)


class OpenMeteoAdapter:
    def __init__(self, http: JsonHttpClient | None = None) -> None:
        self.http = http or JsonHttpClient()

    def fetch_live(
        self,
        location: Location,
        model: OpenMeteoModel,
        forecast_hours: int = 72,
    ) -> Forecast:
        payload = self.http.get(
            OPEN_METEO_LIVE_URL,
            self._params(location, model, forecast_hours),
        )
        return parse_open_meteo(payload, location, model)

    def fetch_single_run(
        self,
        location: Location,
        model: OpenMeteoModel,
        run: str,
        forecast_hours: int = 72,
    ) -> Forecast:
        params = self._params(location, model, forecast_hours)
        params["run"] = run
        payload = self.http.get(OPEN_METEO_SINGLE_RUN_URL, params)
        forecast = parse_open_meteo(payload, location, model)
        return Forecast(
            origin=Origin(
                provider=forecast.origin.provider,
                model_family=forecast.origin.model_family,
                model_id=forecast.origin.model_id,
                generated_at=forecast.origin.generated_at,
                model_run=normalize_iso_utc(run),
            ),
            location=forecast.location,
            hourly=forecast.hourly,
        )

    @staticmethod
    def _params(
        location: Location,
        model: OpenMeteoModel,
        forecast_hours: int,
    ) -> dict[str, str]:
        if forecast_hours < 1 or forecast_hours > 384:
            raise ValueError("forecast_hours must be between 1 and 384")
        return {
            "latitude": f"{location.latitude:.4f}",
            "longitude": f"{location.longitude:.4f}",
            "models": model.model_id,
            "hourly": ",".join(HOURLY_FIELDS),
            "forecast_hours": str(forecast_hours),
            "timezone": "UTC",
            "timeformat": "unixtime",
            "temperature_unit": "celsius",
            "wind_speed_unit": "ms",
            "precipitation_unit": "mm",
        }


class MetNorwayAdapter:
    def __init__(self, http: JsonHttpClient | None = None) -> None:
        self.http = http or JsonHttpClient()

    def fetch_live(self, location: Location, forecast_hours: int = 72) -> Forecast:
        payload = self.http.get(
            MET_NORWAY_URL,
            {
                "lat": f"{location.latitude:.4f}",
                "lon": f"{location.longitude:.4f}",
            },
        )
        return parse_met_norway(payload, location, forecast_hours)


def _series(hourly: Mapping[str, Any], base_name: str) -> Sequence[Any] | None:
    direct = hourly.get(base_name)
    if isinstance(direct, list):
        return direct

    candidates = [
        value
        for key, value in hourly.items()
        if key.startswith(f"{base_name}_") and isinstance(value, list)
    ]
    if len(candidates) == 1:
        return candidates[0]
    if len(candidates) > 1:
        raise ValueError(f"Ambiguous Open-Meteo field {base_name}")
    return None


def _at(values: Sequence[Any] | None, index: int) -> float | None:
    if values is None or index >= len(values):
        return None
    value = values[index]
    if value is None:
        return None
    return float(value)


def parse_open_meteo(
    payload: Mapping[str, Any],
    location: Location,
    model: OpenMeteoModel,
) -> Forecast:
    hourly = payload.get("hourly")
    if not isinstance(hourly, Mapping):
        raise ValueError("Open-Meteo response has no hourly object")

    times = hourly.get("time")
    if not isinstance(times, list):
        raise ValueError("Open-Meteo response has no hourly time array")

    fields = {name: _series(hourly, name) for name in HOURLY_FIELDS}

    points = []
    for index, raw_time in enumerate(times):
        if isinstance(raw_time, (int, float)):
            time = utc_iso_from_epoch(raw_time)
        else:
            time = normalize_iso_utc(str(raw_time))

        points.append(
            HourlyPoint(
                time=time,
                temperature_c=_at(fields["temperature_2m"], index),
                feels_like_c=_at(fields["apparent_temperature"], index),
                dew_point_c=_at(fields["dew_point_2m"], index),
                humidity_percent=_at(fields["relative_humidity_2m"], index),
                pressure_sea_level_hpa=_at(fields["pressure_msl"], index),
                wind_speed_mps=_at(fields["wind_speed_10m"], index),
                wind_gust_mps=_at(fields["wind_gusts_10m"], index),
                wind_direction_degrees=_at(fields["wind_direction_10m"], index),
                precipitation_mm=_at(fields["precipitation"], index),
                precipitation_probability_percent=_at(
                    fields["precipitation_probability"], index
                ),
                cloud_cover_percent=_at(fields["cloud_cover"], index),
                visibility_meters=_at(fields["visibility"], index),
            )
        )

    return Forecast(
        origin=Origin(
            provider="OPEN_METEO",
            model_family=model.model_family,
            model_id=model.model_id,
        ),
        location=location,
        hourly=tuple(points),
    )


def parse_met_norway(
    payload: Mapping[str, Any],
    location: Location,
    forecast_hours: int,
) -> Forecast:
    properties = payload.get("properties")
    if not isinstance(properties, Mapping):
        raise ValueError("MET Norway response has no properties object")

    raw_series = properties.get("timeseries")
    if not isinstance(raw_series, list):
        raise ValueError("MET Norway response has no timeseries array")

    points: list[HourlyPoint] = []
    for entry in raw_series[:forecast_hours]:
        if not isinstance(entry, Mapping):
            continue

        data = entry.get("data")
        if not isinstance(data, Mapping):
            continue

        instant = data.get("instant")
        instant_details = (
            instant.get("details")
            if isinstance(instant, Mapping)
            else {}
        )
        if not isinstance(instant_details, Mapping):
            instant_details = {}

        next_hour = data.get("next_1_hours")
        next_hour_details = (
            next_hour.get("details")
            if isinstance(next_hour, Mapping)
            else {}
        )
        if not isinstance(next_hour_details, Mapping):
            next_hour_details = {}

        points.append(
            HourlyPoint(
                time=normalize_iso_utc(str(entry["time"])),
                temperature_c=_number(instant_details, "air_temperature"),
                humidity_percent=_number(instant_details, "relative_humidity"),
                pressure_sea_level_hpa=_number(
                    instant_details, "air_pressure_at_sea_level"
                ),
                wind_speed_mps=_number(instant_details, "wind_speed"),
                wind_gust_mps=_number(instant_details, "wind_speed_of_gust"),
                wind_direction_degrees=_number(
                    instant_details, "wind_from_direction"
                ),
                precipitation_mm=_number(
                    next_hour_details, "precipitation_amount"
                ),
                precipitation_probability_percent=_number(
                    next_hour_details, "probability_of_precipitation"
                ),
                cloud_cover_percent=_number(
                    instant_details, "cloud_area_fraction"
                ),
            )
        )

    updated_at = properties.get("meta", {}).get("updated_at")
    return Forecast(
        origin=Origin(
            provider="MET_NORWAY",
            # Locationforecast global output outside the Nordic area is ECMWF-derived.
            # Keep it in the ECMWF family so fusion does not double-count it.
            model_family="ECMWF_IFS",
            model_id="metno_locationforecast_global",
            generated_at=normalize_iso_utc(updated_at)
            if isinstance(updated_at, str)
            else None,
        ),
        location=location,
        hourly=tuple(points),
    )


def _number(mapping: Mapping[str, Any], key: str) -> float | None:
    value = mapping.get(key)
    if value is None:
        return None
    return float(value)
