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
    MAX_NOAA_RESPONSE_BYTES,
    NOAA_FIELDS,
    NOAA_REQUEST_SPACING_SECONDS,
    _fetch_bounded,
    _run_tokens,
    _save_sample,
    _sha256,
    _template_summary,
    parser,
    probe_dwd,
    probe_ecmwf,
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


def _build_payload(
    *,
    model_run: datetime,
    forecast_hour: int,
    samples: list[DownloadedSample],
    errors: list[dict[str, str]],
) -> dict[str, object]:
    return {
        "schema_version": 2,
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
        "errors": errors,
        "samples": [sample.to_dict() for sample in samples],
    }


def main() -> None:
    args = parser().parse_args()
    args.samples_dir.mkdir(parents=True, exist_ok=True)

    samples: list[DownloadedSample] = []
    errors: list[dict[str, str]] = []
    providers: tuple[
        tuple[
            str,
            Callable[[datetime, int, Path], list[DownloadedSample]],
        ],
        ...,
    ] = (
        ("NOAA_NOMADS", probe_noaa_resilient),
        ("ECMWF_OPEN_DATA", probe_ecmwf),
        ("DWD_OPEN_DATA", probe_dwd),
    )

    for provider, probe in providers:
        try:
            samples.extend(probe(args.run, args.forecast_hour, args.samples_dir))
        except Exception as error:  # noqa: BLE001 - evidence must retain other providers
            errors.append(
                {
                    "provider": provider,
                    "exception": type(error).__name__,
                    "message": str(error),
                }
            )

    payload = _build_payload(
        model_run=args.run,
        forecast_hour=args.forecast_hour,
        samples=samples,
        errors=errors,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))

    if errors:
        providers_failed = ", ".join(error["provider"] for error in errors)
        raise RuntimeError(
            f"GRIB capability probe completed with provider failures: {providers_failed}"
        )


if __name__ == "__main__":
    main()
