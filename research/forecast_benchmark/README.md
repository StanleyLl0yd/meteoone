# MeteoOne forecast benchmark

This directory contains the M0 research harness used to compare forecast model families before production weights are selected.

It is intentionally isolated from the Android application and has no third-party Python dependencies.

## Models

The initial explicit model set is:

| Provider | API model id | MeteoOne model family |
| --- | --- | --- |
| Open-Meteo | `ecmwf_ifs` | ECMWF_IFS |
| Open-Meteo | `icon_global` | DWD_ICON |
| Open-Meteo | `ncep_gfs_global` | NOAA_GFS |
| MET Norway | Locationforecast global | ECMWF_IFS |

MET Norway global data is intentionally assigned to the ECMWF model family for correlation-aware fusion. It must not become an independent fourth model vote merely because it arrives from another API.

## Live collection

From the repository root:

```bash
python3 -m research.forecast_benchmark.cli locations

python3 -m research.forecast_benchmark.cli live \
  --location saint-petersburg \
  --hours 72 \
  --output research-output/spb-live.jsonl
```

The output is newline-delimited canonical JSON, one forecast per provider/model origin.

## Archived single run

Open-Meteo Single Runs can preserve an individual model initialization and forecast horizon:

```bash
python3 -m research.forecast_benchmark.cli single-run \
  --location moscow \
  --run 2026-09-01T00:00Z \
  --hours 72 \
  --output research-output/moscow-20260901T00.jsonl
```

Do not commit generated research output unless it is a deliberately small reviewed fixture.

## Metrics

Pure local metric helpers currently cover:

- MAE;
- bias;
- RMSE;
- Brier score for binary precipitation events;
- mean wind-vector error;
- lead-time buckets 0–6 h, 6–24 h, 24–48 h and 48–72 h.

## Observation/reference strategy

Forecast skill must eventually be evaluated against real observations, not against another forecast product.

Preferred production-quality research reference:

1. public governmental surface-station observations with clear commercial/research reuse terms;
2. quality-control and station-distance rules;
3. explicit handling of station elevation versus forecast-grid elevation.

Meteostat is useful for exploratory research but its data is currently CC BY-NC 4.0, so it must not become an undisclosed commercial calibration dependency.

Open-Meteo historical/reanalysis products may be used as a temporary research proxy, but a proxy derived from ECMWF analysis can systematically favor ECMWF and must not be used to claim calibrated production accuracy.

## External API policy

Network calls are manual research operations, not blocking PR checks. Unit tests use local fixtures only.

Open-Meteo's public free API is suitable for non-commercial research/evaluation; production usage must follow the applicable current plan/license.

MET Norway requests use an identifying User-Agent and must follow the provider's caching, attribution and traffic requirements.

## Sources verified for M0

- Open-Meteo OpenAPI model identifiers:
  https://github.com/open-meteo/open-meteo/blob/main/openapi/forecast.yml
- Open-Meteo Historical Forecast / Single Runs documentation:
  https://open-meteo.com/en/docs/historical-forecast-api
  https://open-meteo.com/en/docs/single-runs-api
- MET Norway Locationforecast:
  https://api.met.no/weatherapi/locationforecast/2.0/documentation
