from __future__ import annotations

import argparse
import bz2
import hashlib
import json
import re
import time
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable

from .grib2 import Grib2MessageInfo, inspect_grib2


USER_AGENT = (
    "MeteoOne-GRIB-capability-probe/1 "
    "(+https://github.com/StanleyLl0yd/meteoone)"
)
MAX_NOAA_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_ECMWF_INDEX_BYTES = 2 * 1024 * 1024
MAX_ECMWF_FIELD_BYTES = 16 * 1024 * 1024
MAX_DWD_COMPRESSED_BYTES = 8 * 1024 * 1024
MAX_DWD_DECOMPRESSED_BYTES = 64 * 1024 * 1024
NOAA_REQUEST_SPACING_SECONDS = 10
ALLOWED_SOURCE_HOSTS = frozenset(
    {
        "nomads.ncep.noaa.gov",
        "data.ecmwf.int",
        "opendata.dwd.de",
    }
)
CONTENT_RANGE = re.compile(r"^bytes (?P<start>\d+)-(?P<end>\d+)/(?P<total>\d+|\*)$")

NOAA_FIELDS = {
    "temperature_2m": ("TMP", "2_m_above_ground"),
    "dew_point_2m": ("DPT", "2_m_above_ground"),
    "relative_humidity_2m": ("RH", "2_m_above_ground"),
    "pressure_mean_sea_level": ("PRMSL", "mean_sea_level"),
    "wind_u_10m": ("UGRD", "10_m_above_ground"),
    "wind_v_10m": ("VGRD", "10_m_above_ground"),
    "wind_gust_10m": ("GUST", "surface"),
    "total_precipitation": ("APCP", "surface"),
    "total_cloud_cover": ("TCDC", "entire_atmosphere"),
    "visibility": ("VIS", "surface"),
}
# Measured on GFS 0.25 f006: APCP can contain more than one interval message.
# Keep the allowance narrow and bounded; point-like fields still require exactly one.
NOAA_MAX_MESSAGES = {
    "total_precipitation": 4,
}

ECMWF_FIELDS = {
    "temperature_2m": ("2t",),
    "dew_point_2m": ("2d",),
    "pressure_mean_sea_level": ("msl",),
    "wind_u_10m": ("10u",),
    "wind_v_10m": ("10v",),
    "wind_gust_10m": ("10fg3", "max_i10fg"),
    "total_precipitation": ("tp",),
    "total_cloud_cover": ("tcc",),
}

DWD_FIELDS = {
    "temperature_2m": ("t_2m", "T_2M"),
    "dew_point_2m": ("td_2m", "TD_2M"),
    "relative_humidity_2m": ("relhum_2m", "RELHUM_2M"),
    "pressure_mean_sea_level": ("pmsl", "PMSL"),
    "wind_u_10m": ("u_10m", "U_10M"),
    "wind_v_10m": ("v_10m", "V_10M"),
    "wind_gust_10m": ("vmax_10m", "VMAX_10M"),
    "total_precipitation": ("tot_prec", "TOT_PREC"),
    "total_cloud_cover": ("clct", "CLCT"),
    "weather_code": ("ww", "WW"),
}


@dataclass(frozen=True)
class DownloadedSample:
    provider: str
    field: str
    source_url: str
    final_url: str
    response_status: int
    raw_size: int
    raw_sha256: str
    messages: tuple[Grib2MessageInfo, ...]
    compressed_size: int | None = None
    compressed_sha256: str | None = None
    index_metadata: dict[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["messages"] = [message.to_dict() for message in self.messages]
        return payload


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _validate_source_url(url: str, *, expected_host: str | None = None) -> str:
    parsed = urllib.parse.urlsplit(url)
    try:
        port = parsed.port
    except ValueError as error:
        raise RuntimeError("Official-source URL has an invalid port") from error

    host = parsed.hostname
    if (
        parsed.scheme != "https"
        or host not in ALLOWED_SOURCE_HOSTS
        or parsed.username is not None
        or parsed.password is not None
        or port not in (None, 443)
    ):
        raise RuntimeError("Official-source URL must use HTTPS on an approved host")
    if expected_host is not None and host != expected_host:
        raise RuntimeError("Cross-host redirect from an official source is forbidden")
    return host


def _parse_content_length(value: str, *, url: str) -> int:
    try:
        length = int(value)
    except ValueError as error:
        raise RuntimeError(f"Response from {url} has an invalid Content-Length") from error
    if length < 0:
        raise RuntimeError(f"Response from {url} has a negative Content-Length")
    return length


def _validate_content_range(value: str | None, *, start: int, end: int) -> None:
    if value is None:
        raise RuntimeError("ECMWF range response is missing Content-Range")
    match = CONTENT_RANGE.fullmatch(value.strip())
    if match is None:
        raise RuntimeError("ECMWF range response has an invalid Content-Range")
    if int(match.group("start")) != start or int(match.group("end")) != end:
        raise RuntimeError("ECMWF range response does not match the requested byte range")
    total = match.group("total")
    if total != "*" and int(total) <= end:
        raise RuntimeError("ECMWF Content-Range declares an impossible total length")


def _fetch_bounded(
    url: str,
    *,
    max_bytes: int,
    headers: dict[str, str] | None = None,
    require_status: int | None = None,
    expected_range: tuple[int, int] | None = None,
) -> tuple[bytes, int, str]:
    if max_bytes <= 0:
        raise ValueError("max_bytes must be positive")
    origin_host = _validate_source_url(url)
    request_headers = {"User-Agent": USER_AGENT, "Accept-Encoding": "identity"}
    if headers:
        request_headers.update(headers)
    request = urllib.request.Request(url, headers=request_headers)

    with urllib.request.urlopen(  # nosemgrep: python.lang.security.audit.dynamic-urllib-use-detected.dynamic-urllib-use-detected
        request,
        timeout=30,
    ) as response:
        status = response.status
        final_url = response.geturl()
        _validate_source_url(final_url, expected_host=origin_host)
        if require_status is not None and status != require_status:
            raise RuntimeError(
                f"Expected HTTP {require_status} from {url}, received {status}"
            )
        declared_length = response.headers.get("Content-Length")
        if declared_length is not None:
            parsed_length = _parse_content_length(declared_length, url=url)
            if parsed_length > max_bytes:
                raise RuntimeError(
                    f"Response from {url} declares {parsed_length} bytes; limit is {max_bytes}"
                )
        if expected_range is not None:
            _validate_content_range(
                response.headers.get("Content-Range"),
                start=expected_range[0],
                end=expected_range[1],
            )
        payload = response.read(max_bytes + 1)

    if len(payload) > max_bytes:
        raise RuntimeError(f"Response from {url} exceeded {max_bytes} bytes")
    return payload, status, final_url


def _save_sample(samples_dir: Path, provider: str, field: str, data: bytes, suffix: str) -> Path:
    target = samples_dir / provider / f"{field}{suffix}"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return target


def _run_tokens(model_run: datetime) -> tuple[str, str, str]:
    run_utc = model_run.astimezone(timezone.utc)
    date = run_utc.strftime("%Y%m%d")
    cycle = run_utc.strftime("%H")
    return date, cycle, f"{date}{cycle}"


def probe_noaa(
    model_run: datetime,
    forecast_hour: int,
    samples_dir: Path,
) -> list[DownloadedSample]:
    date, cycle, _ = _run_tokens(model_run)
    forecast_token = f"{forecast_hour:03d}"
    file_name = f"gfs.t{cycle}z.pgrb2.0p25.f{forecast_token}"
    directory = f"/gfs.{date}/{cycle}/atmos"
    # Paris snapped to the same 0.25-degree grid used by the production planner.
    longitude = "2.25"
    latitude = "48.75"

    samples: list[DownloadedSample] = []
    for index, (field, (variable, level)) in enumerate(NOAA_FIELDS.items()):
        query = urllib.parse.urlencode(
            [
                ("file", file_name),
                (f"var_{variable}", "on"),
                (f"lev_{level}", "on"),
                ("subregion", ""),
                ("leftlon", longitude),
                ("rightlon", longitude),
                ("toplat", latitude),
                ("bottomlat", latitude),
                ("dir", directory),
            ]
        )
        url = f"https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl?{query}"
        raw, status, final_url = _fetch_bounded(
            url,
            max_bytes=MAX_NOAA_RESPONSE_BYTES,
            require_status=200,
        )
        messages = inspect_grib2(raw)
        max_messages = NOAA_MAX_MESSAGES.get(field, 1)
        if not 1 <= len(messages) <= max_messages:
            raise RuntimeError(
                f"NOAA field {field} returned {len(messages)} GRIB messages; "
                f"expected 1..{max_messages}"
            )
        _save_sample(samples_dir, "noaa", field, raw, ".grib2")
        samples.append(
            DownloadedSample(
                provider="NOAA_NOMADS",
                field=field,
                source_url=url,
                final_url=final_url,
                response_status=status,
                raw_size=len(raw),
                raw_sha256=_sha256(raw),
                messages=messages,
            )
        )
        if index != len(NOAA_FIELDS) - 1:
            time.sleep(NOAA_REQUEST_SPACING_SECONDS)
    return samples


def _load_ecmwf_index(
    model_run: datetime,
    forecast_hour: int,
    *,
    raw_output: Path | None = None,
) -> tuple[str, str, list[dict[str, Any]]]:
    date, cycle, _ = _run_tokens(model_run)
    prefix = (
        f"https://data.ecmwf.int/forecasts/{date}/{cycle}z/ifs/0p25/oper/"
        f"{date}{cycle}0000-{forecast_hour}h-oper-fc"
    )
    index_url = f"{prefix}.index"
    grib_url = f"{prefix}.grib2"
    raw, _, _ = _fetch_bounded(
        index_url,
        max_bytes=MAX_ECMWF_INDEX_BYTES,
        require_status=200,
    )
    if raw_output is not None:
        raw_output.parent.mkdir(parents=True, exist_ok=True)
        raw_output.write_bytes(raw)
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise RuntimeError("ECMWF index is not valid UTF-8") from error

    entries: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise RuntimeError(f"Invalid ECMWF JSON on line {line_number}") from error
        if not isinstance(value, dict):
            raise RuntimeError(f"ECMWF index line {line_number} is not an object")
        entries.append(value)
    if not entries:
        raise RuntimeError("ECMWF index is empty")
    return index_url, grib_url, entries


def _validate_ecmwf_entry(
    entry: dict[str, Any],
    *,
    date: str,
    cycle: str,
    forecast_hour: int,
) -> None:
    expected = {
        "domain": "g",
        "date": date,
        "time": f"{cycle}00",
        "class": "od",
        "type": "fc",
        "stream": "oper",
        "step": str(forecast_hour),
        "levtype": "sfc",
    }
    for key, expected_value in expected.items():
        if entry.get(key) != expected_value:
            raise RuntimeError(
                f"ECMWF index provenance mismatch for {key}: "
                f"expected {expected_value!r}, got {entry.get(key)!r}"
            )


def probe_ecmwf(
    model_run: datetime,
    forecast_hour: int,
    samples_dir: Path,
) -> list[DownloadedSample]:
    date, cycle, _ = _run_tokens(model_run)
    index_url, grib_url, entries = _load_ecmwf_index(model_run, forecast_hour)
    samples: list[DownloadedSample] = []

    for field, parameter_candidates in ECMWF_FIELDS.items():
        matches = [
            entry
            for entry in entries
            if entry.get("levtype") == "sfc"
            and entry.get("param") in parameter_candidates
        ]
        if len(matches) != 1:
            raise RuntimeError(
                f"ECMWF field {field} matched {len(matches)} entries for {parameter_candidates}"
            )
        entry = matches[0]
        _validate_ecmwf_entry(
            entry,
            date=date,
            cycle=cycle,
            forecast_hour=forecast_hour,
        )
        offset = entry.get("_offset")
        length = entry.get("_length")
        if isinstance(offset, bool) or not isinstance(offset, int) or offset < 0:
            raise RuntimeError(f"ECMWF field {field} has invalid _offset")
        if isinstance(length, bool) or not isinstance(length, int) or length <= 0:
            raise RuntimeError(f"ECMWF field {field} has invalid _length")
        if length > MAX_ECMWF_FIELD_BYTES:
            raise RuntimeError(
                f"ECMWF field {field} has {length} bytes; limit is {MAX_ECMWF_FIELD_BYTES}"
            )
        end = offset + length - 1
        if end < offset:
            raise RuntimeError(f"ECMWF field {field} byte range overflowed")

        raw, status, final_url = _fetch_bounded(
            grib_url,
            max_bytes=length,
            headers={"Range": f"bytes={offset}-{end}"},
            require_status=206,
            expected_range=(offset, end),
        )
        if len(raw) != length:
            raise RuntimeError(
                f"ECMWF field {field} returned {len(raw)} bytes; expected {length}"
            )
        messages = inspect_grib2(raw)
        if len(messages) != 1:
            raise RuntimeError(
                f"ECMWF field {field} returned {len(messages)} GRIB messages; expected 1"
            )
        _save_sample(samples_dir, "ecmwf", field, raw, ".grib2")
        metadata = {
            key: value
            for key, value in entry.items()
            if not key.startswith("_")
        }
        metadata["index_url"] = index_url
        metadata["range"] = f"bytes={offset}-{end}"
        samples.append(
            DownloadedSample(
                provider="ECMWF_OPEN_DATA",
                field=field,
                source_url=grib_url,
                final_url=final_url,
                response_status=status,
                raw_size=len(raw),
                raw_sha256=_sha256(raw),
                messages=messages,
                index_metadata=metadata,
            )
        )
    return samples


def _decompress_bzip2_bounded(data: bytes, *, max_bytes: int) -> bytes:
    decompressor = bz2.BZ2Decompressor()
    output = decompressor.decompress(data, max_length=max_bytes + 1)
    if len(output) > max_bytes:
        raise RuntimeError(f"Bzip2 output exceeded {max_bytes} bytes")
    if not decompressor.eof:
        raise RuntimeError("Bzip2 payload is truncated or exceeds the decompression limit")
    if decompressor.unused_data:
        raise RuntimeError("Bzip2 payload contains unexpected trailing data")
    return output


def probe_dwd(
    model_run: datetime,
    forecast_hour: int,
    samples_dir: Path,
) -> list[DownloadedSample]:
    date, cycle, run_token = _run_tokens(model_run)
    del date
    forecast_token = f"{forecast_hour:03d}"
    samples: list[DownloadedSample] = []

    for field, (directory, variable) in DWD_FIELDS.items():
        url = (
            f"https://opendata.dwd.de/weather/nwp/icon/grib/{cycle}/{directory}/"
            f"icon_global_icosahedral_single-level_{run_token}_{forecast_token}_{variable}.grib2.bz2"
        )
        compressed, status, final_url = _fetch_bounded(
            url,
            max_bytes=MAX_DWD_COMPRESSED_BYTES,
            require_status=200,
        )
        raw = _decompress_bzip2_bounded(
            compressed,
            max_bytes=MAX_DWD_DECOMPRESSED_BYTES,
        )
        messages = inspect_grib2(raw)
        if len(messages) != 1:
            raise RuntimeError(
                f"DWD field {field} returned {len(messages)} GRIB messages; expected 1"
            )
        _save_sample(samples_dir, "dwd", field, compressed, ".grib2.bz2")
        samples.append(
            DownloadedSample(
                provider="DWD_OPEN_DATA",
                field=field,
                source_url=url,
                final_url=final_url,
                response_status=status,
                raw_size=len(raw),
                raw_sha256=_sha256(raw),
                messages=messages,
                compressed_size=len(compressed),
                compressed_sha256=_sha256(compressed),
            )
        )
    return samples


def _template_summary(samples: Iterable[DownloadedSample]) -> dict[str, list[int]]:
    grid: set[int] = set()
    product: set[int] = set()
    representation: set[int] = set()
    for sample in samples:
        for message in sample.messages:
            grid.add(message.grid_definition_template)
            product.add(message.product_definition_template)
            representation.add(message.data_representation_template)
    return {
        "grid_definition_templates": sorted(grid),
        "product_definition_templates": sorted(product),
        "data_representation_templates": sorted(representation),
    }


def _parse_run(value: str) -> datetime:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:00Z", value):
        raise argparse.ArgumentTypeError(
            "--run must use exact UTC form YYYY-MM-DDTHH:00Z"
        )
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%MZ").replace(tzinfo=timezone.utc)
    except ValueError as error:
        raise argparse.ArgumentTypeError("--run is not a valid UTC timestamp") from error
    if parsed.hour not in {0, 6, 12, 18}:
        raise argparse.ArgumentTypeError("--run must use an operational 00/06/12/18Z cycle")
    return parsed


def _parse_forecast_hour(value: str) -> int:
    try:
        hour = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("--forecast-hour must be an integer") from error
    if hour not in range(3, 73, 3):
        raise argparse.ArgumentTypeError(
            "--forecast-hour must be a common 3-hour step from 3 through 72"
        )
    return hour


def parser() -> argparse.ArgumentParser:
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--run", type=_parse_run, required=True)
    cli.add_argument("--forecast-hour", type=_parse_forecast_hour, required=True)
    cli.add_argument("--output", type=Path, required=True)
    cli.add_argument("--samples-dir", type=Path, required=True)
    return cli


def main() -> None:
    args = parser().parse_args()
    args.samples_dir.mkdir(parents=True, exist_ok=True)

    samples: list[DownloadedSample] = []
    samples.extend(probe_noaa(args.run, args.forecast_hour, args.samples_dir))
    samples.extend(probe_ecmwf(args.run, args.forecast_hour, args.samples_dir))
    samples.extend(probe_dwd(args.run, args.forecast_hour, args.samples_dir))

    payload = {
        "schema_version": 1,
        "probed_at": datetime.now(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
        "model_run": args.run.isoformat().replace("+00:00", "Z"),
        "forecast_hour": args.forecast_hour,
        "sample_count": len(samples),
        "providers": {
            provider: _template_summary(
                sample for sample in samples if sample.provider == provider
            )
            for provider in sorted({sample.provider for sample in samples})
        },
        "samples": [sample.to_dict() for sample in samples],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
