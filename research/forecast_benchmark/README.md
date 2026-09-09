# MeteoOne forecast benchmark

This directory contains the M0 research harness used to compare forecast model families before production weights are selected.

It is isolated from the Android application, uses only the Python standard library, and keeps network collection out of blocking PR checks.

## Models

| Provider | API model id | MeteoOne model family |
| --- | --- | --- |
| Open-Meteo | `ecmwf_ifs` | ECMWF_IFS |
| Open-Meteo | `icon_global` | DWD_ICON |
| Open-Meteo | `ncep_gfs_global` | NOAA_GFS |
| MET Norway | Locationforecast global | ECMWF_IFS |

MET Norway global data is intentionally assigned to the ECMWF model family for correlation-aware fusion. It must not become an independent fourth model vote merely because it arrives through another API.

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

## Archived model runs

Collect one initialization:

```bash
python3 -m research.forecast_benchmark.cli single-run \
  --location moscow \
  --run 2026-09-01T00:00Z \
  --hours 72 \
  --output research-output/moscow-20260901T00.jsonl
```

Collect a date range while isolating individual model/provider failures:

```bash
python3 -m research.forecast_benchmark.cli batch-runs \
  --start 2026-09-01 \
  --end 2026-09-07 \
  --cycles 0 \
  --hours 72 \
  --output research-output/forecasts.jsonl
```

Omitting `--location` collects every location in `locations.json`. Repeat `--location` to restrict the batch. The default is one 00 UTC initialization per day to keep initial research traffic bounded. Explicit `--cycles 0,6,12,18` enables all common global cycles.

Successful forecasts are preserved even if another model request fails. Failures are written to a sibling `*-errors.jsonl` file unless `--errors` is supplied.

For a common ECMWF/ICON/GFS comparison window, use dates supported by all three Open-Meteo Single Runs archives. At the time this M0 methodology was established, the limiting non-ECMWF archives begin on 2026-04-02. Re-check provider documentation before future benchmark campaigns.

## Observation reference: NOAA/NCEI ISD

The benchmark reference is real surface observations from NOAA/NCEI Integrated Surface Database / Global Hourly, not forecast or reanalysis output.

Select the station first:

```bash
python3 -m research.forecast_benchmark.cli station \
  --location moscow \
  --start 2026-09-01 \
  --end 2026-09-07
```

Then collect the observations:

```bash
python3 -m research.forecast_benchmark.cli observations \
  --location moscow \
  --start 2026-09-01 \
  --end 2026-09-07 \
  --output research-output/moscow-observations.json
```

Station selection rules are deliberately explicit:

- the station must cover the complete requested period;
- the default station radius is at most 75 km from the benchmark location;
- if `--target-elevation-m` is supplied, the station must publish elevation and the default maximum elevation difference is 300 m;
- no automatic lapse-rate or pressure/elevation correction is applied in M0;
- station identity, coordinates and elevation remain in the observation artifact for auditability.

The benchmark accepts ISD quality flags `0`, `1`, `4`, `5` and `9`. Suspect or erroneous observations are treated as missing rather than repaired or fabricated.

For precipitation, the initial benchmark uses one-hour `AA1`..`AA4` amounts. Trace precipitation is preserved as an event for Brier scoring even when the measured depth is zero. Inaccurate, deleted, or incompatible accumulation records are excluded.

## Scoring

```bash
python3 -m research.forecast_benchmark.cli score \
  --location moscow \
  --forecasts research-output/forecasts.jsonl \
  --observations research-output/moscow-observations.json \
  --output research-output/moscow-score.json
```

Forecast valid times are matched to the nearest observation within 30 minutes by default. Observation input order is normalized before matching.

Metrics:

- temperature: MAE, bias and RMSE;
- sea-level pressure: MAE, bias and RMSE;
- one-hour precipitation amount: MAE, bias and RMSE;
- precipitation probability: Brier score against observed precipitation events when the probability source has explicit provenance;
- wind: mean vector error;
- lead buckets: 0–6 h, 6–24 h, 24–48 h and 48–72 h.

Every score carries its usable sample count. Missing data is excluded parameter-by-parameter; it is never replaced with zero or another synthetic value.

### Probability provenance

The explicit `ecmwf_ifs`, `icon_global` and `ncep_gfs_global` benchmark requests are deterministic model runs. They intentionally do not request Open-Meteo `precipitation_probability`.

Open-Meteo documents ICON precipitation probability as derived from ICON-EPS members and GFS probability as derived from GEFS members, while the deterministic ECMWF API does not expose an equivalent precipitation-probability field. Treating those values as if they were probabilities emitted by the deterministic runs would corrupt model provenance.

The Brier implementation therefore remains available for a future explicitly modelled probabilistic source, but Brier is excluded from deterministic scalar-weight calibration unless that separate source and family relationship are recorded.

## Weighting policy

M0 production fusion remains equal-weight until this benchmark has sufficient real-observation coverage.

Measured weights must be derived from reproducible historical skill, remain model-family aware, and be documented with the benchmark period, locations, lead bucket, parameter and usable sample counts. MET Norway must not increase ECMWF's independent evidence weight.

A small pilot may validate the pipeline, but it must not be presented as calibrated production accuracy.

## Reproducibility and generated data

Do not commit bulk generated research output. Keep only deliberately small reviewed fixtures when they protect parser or scoring behavior.

Network calls are manual research operations. CI executes local fixture/unit tests only, so provider availability and rate limits cannot make a PR fail.

Meteostat may be useful for exploration but its current CC BY-NC 4.0 dataset must not become an undisclosed commercial calibration dependency.

ECMWF-derived reanalysis can be useful as a diagnostic proxy but must not be used as the reference for production calibration because it can structurally favor ECMWF-derived forecasts.

## External API policy

Open-Meteo's public free API may be used only within the provider's applicable current terms and limits. Production use must follow the applicable commercial/license requirements.

MET Norway requests use an identifying User-Agent and must follow its current caching, attribution and traffic requirements.

NOAA/NCEI station metadata and Global Hourly access are read through public NCEI endpoints. Provider terms and data documentation must be re-checked before a production/release decision.

## Sources verified for M0

- NOAA/NCEI Integrated Surface Database:
  https://www.ncei.noaa.gov/products/land-based-station/integrated-surface-database
- NOAA/NCEI ISD station history:
  https://www.ncei.noaa.gov/pub/data/noaa/isd-history.csv
- NOAA/NCEI Global Hourly access:
  https://www.ncei.noaa.gov/data/global-hourly/access/
- Open-Meteo model identifiers:
  https://github.com/open-meteo/open-meteo/blob/main/openapi/forecast.yml
- Open-Meteo Historical Forecast / Single Runs:
  https://open-meteo.com/en/docs/historical-forecast-api
  https://open-meteo.com/en/docs/single-runs-api
- Open-Meteo model-specific probability provenance:
  https://open-meteo.com/en/docs/dwd-api
  https://open-meteo.com/en/docs/gfs-api
  https://open-meteo.com/en/docs/ecmwf-api
- MET Norway Locationforecast:
  https://api.met.no/weatherapi/locationforecast/2.0/documentation
