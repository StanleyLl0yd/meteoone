from __future__ import annotations

import json
import time
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from .grib2 import inspect_grib2
from .probe import (
    DownloadedSample,
    ECMWF_FIELDS,
    MAX_ECMWF_FIELD_BYTES,
    MAX_ECMWF_INDEX_BYTES,
    MAX_NOAA_RESPONSE_BYTES,
    NOAA_FIELDS,
    NOAA_REQUEST_SPACING_SECONDS,
    _fetch_bounded,
    _load_ecmwf_index,
    _run_tokens,
    _save_sample,
    _sha256,
    _template_summary,
    _validate_ecmwf_entry,
    parser,
    probe_dwd,
)

NOAA_MAX_MESSAGES_PER_FIELD = 4


def probe_noaa_resilient(
    model_run: datetime,
    forecast_hour: int,
    samples_dir: Path,
) -> list[DownloadedSample]:
    date, cycle, _ = _run_tokens(model_run)
    forecast_token = f"{forecast_hour:03d}"
    file_name = f"gfs.t{cycle}z.pgrb2.0p25.f{forecast_token}"
    directory = f"/gfs.{date}/{cycle}/atmos"
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
        if not 1 <= len(messages) <= NOAA_MAX_MESSAGES_PER_FIELD:
            raise RuntimeError(
                f"NOAA field {field} returned {len(messages)} GRIB messages; "
                f"expected 1..{NOAA_MAX_MESSAGES_PER_FIELD}"
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


def _parse_retained_ecmwf_index(raw: bytes) -> list[dict[str, object]]:
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise RuntimeError("Retained ECMWF index is not valid UTF-8") from error

    entries: list[dict[str, object]] = []
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise RuntimeError(
                f"Invalid retained ECMWF JSON on line {line_number}"
            ) from error
        if not isinstance(value, dict):
            raise RuntimeError(
                f"Retained ECMWF index line {line_number} is not an object"
            )
        entries.append(value)
    if not entries:
        raise RuntimeError("Retained ECMWF index is empty")
    return entries


def _surface_param_values(entries: list[dict[str, object]]) -> list[str]:
    return sorted(
        {
            str(entry["param"])
            for entry in entries
            if entry.get("levtype") == "sfc" and entry.get("param") is not None
        }
    )


def probe_ecmwf_resilient(
    model_run: datetime,
    forecast_hour: int,
    samples_dir: Path,
) -> tuple[list[DownloadedSample], list[dict[str, str]], dict[str, object]]:
    date, cycle, _ = _run_tokens(model_run)
    index_url, grib_url, _ = _load_ecmwf_index(model_run, forecast_hour)

    # Fetch the already validated, bounded index once more only for immutable evidence.
    # The retained bytes are parsed again and become the sole entries used below, so
    # field selection always corresponds exactly to the artifact even if an operational
    # listing were to change between the two bounded GETs.
    index_raw, index_status, final_index_url = _fetch_bounded(
        index_url,
        max_bytes=MAX_ECMWF_INDEX_BYTES,
        require_status=200,
    )
    entries = _parse_retained_ecmwf_index(index_raw)
    index_path = _save_sample(
        samples_dir,
        "ecmwf",
        "index",
        index_raw,
        ".jsonl",
    )
    diagnostics: dict[str, object] = {
        "index_url": index_url,
        "final_index_url": final_index_url,
        "index_status": index_status,
        "index_size": index_path.stat().st_size,
        "index_sha256": _sha256(index_raw),
        "surface_param_values": _surface_param_values(entries),
    }

    samples: list[DownloadedSample] = []
    errors: list[dict[str, str]] = []
    for field, parameter_candidates in ECMWF_FIELDS.items():
        try:
            matches = [
                entry
                for entry in entries
                if entry.get("levtype") == "sfc"
                and entry.get("param") in parameter_candidates
            ]
            if len(matches) != 1:
                raise RuntimeError(
                    f"ECMWF field {field} matched {len(matches)} entries "
                    f"for {parameter_candidates}"
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
                    f"ECMWF field {field} has {length} bytes; "
                    f"limit is {MAX_ECMWF_FIELD_BYTES}"
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
        except Exception as error:  # noqa: BLE001 - retain all other field evidence
            errors.append(
                {
                    "provider": "ECMWF_OPEN_DATA",
                    "field": field,
                    "exception": type(error).__name__,
                    "message": str(error),
                }
            )

    return samples, errors, diagnostics


def _build_payload(
    *,
    model_run: datetime,
    forecast_hour: int,
    samples: list[DownloadedSample],
    errors: list[dict[str, str]],
    diagnostics: dict[str, object] | None = None,
) -> dict[str, object]:
    return {
        "schema_version": 3,
        "success": not errors,
        "probed_at": datetime.now(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
        "model_run": model_run.isoformat().replace("+00:00", "Z"),
        "forecast_hour": forecast_hour,
        "sample_count": len(samples),
        "providers": {
            provider: _template_summary(
                sample for sample in samples if sample.provider == provider
            )
            for provider in sorted({sample.provider for sample in samples})
        },
        "diagnostics": diagnostics or {},
        "errors": errors,
        "samples": [sample.to_dict() for sample in samples],
    }


def _capture_provider_failure(
    errors: list[dict[str, str]],
    provider: str,
    error: Exception,
) -> None:
    errors.append(
        {
            "provider": provider,
            "exception": type(error).__name__,
            "message": str(error),
        }
    )


def main() -> None:
    args = parser().parse_args()
    args.samples_dir.mkdir(parents=True, exist_ok=True)

    samples: list[DownloadedSample] = []
    errors: list[dict[str, str]] = []
    diagnostics: dict[str, object] = {}

    independent_providers: tuple[
        tuple[
            str,
            Callable[[datetime, int, Path], list[DownloadedSample]],
        ],
        ...,
    ] = (
        ("NOAA_NOMADS", probe_noaa_resilient),
        ("DWD_OPEN_DATA", probe_dwd),
    )
    for provider, probe in independent_providers:
        try:
            samples.extend(probe(args.run, args.forecast_hour, args.samples_dir))
        except Exception as error:  # noqa: BLE001 - evidence must retain other providers
            _capture_provider_failure(errors, provider, error)

    try:
        ecmwf_samples, ecmwf_errors, ecmwf_diagnostics = probe_ecmwf_resilient(
            args.run,
            args.forecast_hour,
            args.samples_dir,
        )
        samples.extend(ecmwf_samples)
        errors.extend(ecmwf_errors)
        diagnostics["ECMWF_OPEN_DATA"] = ecmwf_diagnostics
    except Exception as error:  # noqa: BLE001 - index-level provider failure
        _capture_provider_failure(errors, "ECMWF_OPEN_DATA", error)

    payload = _build_payload(
        model_run=args.run,
        forecast_hour=args.forecast_hour,
        samples=samples,
        errors=errors,
        diagnostics=diagnostics,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))

    if errors:
        providers_failed = ", ".join(
            sorted({error["provider"] for error in errors})
        )
        raise RuntimeError(
            f"GRIB capability probe completed with provider failures: {providers_failed}"
        )


if __name__ == "__main__":
    main()
