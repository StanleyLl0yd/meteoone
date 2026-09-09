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

## Observation reference: Roshydromet WIS2 / SYNOP

The current Russian M0 truth source is direct surface observation data published by Roshydromet through WIS2, not forecast or reanalysis output.

Dataset:

`urn:wmo:md:ru-roshydromet:core.surface-based-observations.synop`

The dataset is published under the WMO `core` data policy. MeteoOne keeps a reviewed WIGOS mapping for all 10 M0 benchmark locations.

Select the configured station first:

```bash
python3 -m research.forecast_benchmark.cli wis2-station \
  --location moscow \
  --start 2026-08-01 \
  --end 2026-08-31
```

Then collect observations:

```bash
python3 -m research.forecast_benchmark.cli wis2-observations \
  --location moscow \
  --start 2026-08-01 \
  --end 2026-08-31 \
  --output research-output/moscow-wis2-observations.json
```

Acceptance rules are deliberately strict:

- the configured station must cover the complete requested period;
- the default station radius is at most 75 km from the benchmark location;
- if target elevation is supplied, the station must publish elevation and the default maximum elevation difference is 300 m;
- the default usable coverage requirement is 98% for each core deterministic field;
- expected SYNOP cadence is 3 hours;
- units must match the expected Roshydromet projection exactly;
- non-finite and physically implausible values are rejected;
- conflicting duplicate measurements are treated as missing;
- WIGOS station identity must match every accepted feature;
- no automatic lapse-rate or pressure/elevation correction is applied in M0.

The current Roshydromet OGC projection does not expose an explicit source QC flag. The benchmark therefore does not claim one exists. The exact downloaded OGC response is persisted byte-for-byte alongside parsed provenance so the input can be reproduced and audited.

### WIS2 transport caveat

Roshydromet currently publishes this OGC endpoint over HTTP. A 2026-09-09 GitHub-hosted transport probe confirmed that HTTPS on port 443 timed out while the published HTTP endpoint returned HTTP 200 with the expected SYNOP FeatureCollection.

These observations are public and contain no MeteoOne secrets, but the HTTP transport is unauthenticated and does not provide transport integrity. Persisting the exact payload and hashing an artifact can prove later file identity; it cannot authenticate what was received over the network.

### Precipitation semantics

Roshydromet SYNOP precipitation is not an hourly precipitation series. Values commonly represent multi-hour accumulation intervals, often 12 hours, with exact boundaries encoded in `phenomenonTime`; schedules can differ by station.

The adapter therefore stores precipitation separately as `ObservedPrecipitationInterval`. It does not map those accumulations into `ObservedPoint.precipitation_mm`, does not compare a multi-hour accumulation with one forecast hour, and does not invent trace semantics for negative values.

For the current M0 weight campaign, precipitation is excluded from fusion-weight determination unless an explicit interval methodology is implemented and tested that sums model precipitation over the exact observed interval using deterministic, non-overlapping intervals.

### Legacy NOAA/NCEI ISD support

NOAA/NCEI ISD / Global Hourly remains useful for legacy and historical work where coverage is sufficient. It is not the practical truth source for the selected August 2026 Russian M0 campaign because recent Russian coverage is insufficient.

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
- one-hour precipitation amount: MAE, bias and RMSE when a compatible hourly observation series exists; the current WIS2 campaign does not synthesize hourly precipitation;
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

Unequal weights require an advantage that is measurable, material, reasonably stable by geography and lead bucket, and supported by sufficient usable samples. The M0 campaign should also check a simple time stability split such as first-half versus second-half or odd versus even initialization dates. If rankings change materially across location, lead, metric, or time split, equal weights remain the evidence-backed baseline.

A small pilot may validate the pipeline, but it must not be presented as calibrated production accuracy.

### Stability analysis

After collecting the complete campaign and one observation JSON per location, run the repository-owned stability analysis instead of choosing a model from one pooled metric:

```bash
python3 -m research.forecast_benchmark.cli stability \
  --forecasts research-output/forecasts.jsonl \
  --observations saint-petersburg=research-output/observations/saint-petersburg.json \
  --observations moscow=research-output/observations/moscow.json \
  --observations kazan=research-output/observations/kazan.json \
  --observations yekaterinburg=research-output/observations/yekaterinburg.json \
  --observations novosibirsk=research-output/observations/novosibirsk.json \
  --observations krasnoyarsk=research-output/observations/krasnoyarsk.json \
  --observations sochi=research-output/observations/sochi.json \
  --observations vladivostok=research-output/observations/vladivostok.json \
  --observations yakutsk=research-output/observations/yakutsk.json \
  --observations murmansk=research-output/observations/murmansk.json \
  --output research-output/stability.json
```

The command scores the complete campaign plus first-half, second-half, odd-date and even-date initialization splits. It reports pooled skill, winner/margin by lead bucket and split, and winner counts across location/lead cells for temperature MAE, pressure MAE and wind-vector error. It also preserves each split aggregate in the machine-readable output.

The command deliberately does **not** assign production weights automatically. The output is evidence for the documented M0 decision; materiality, consistency and sample counts still have to be reviewed before changing fusion behavior.

## Reproducibility and generated data

Do not commit bulk generated research output. Keep only deliberately small reviewed fixtures when they protect parser or scoring behavior.

Network calls are manual research operations. CI executes local fixture/unit tests only, so provider availability and rate limits cannot make a PR fail.

Meteostat may be useful for exploration but its current CC BY-NC 4.0 dataset must not become an undisclosed commercial calibration dependency.

ECMWF-derived reanalysis can be useful as a diagnostic proxy but must not be used as the reference for production calibration because it can structurally favor ECMWF-derived forecasts.

## External API policy

Open-Meteo's public free API may be used only within the provider's applicable current terms and limits. Production use must follow the applicable commercial/license requirements.

MET Norway requests use an identifying User-Agent and must follow its current caching, attribution and traffic requirements.

Roshydromet WIS2 SYNOP observations are public WMO core data. The currently published OGC endpoint is HTTP-only; MeteoOne must preserve this transport limitation explicitly rather than treating it as authenticated or integrity-protected transport.

NOAA/NCEI station metadata and Global Hourly access remain available for legacy/historical research where coverage is sufficient. Provider terms and data documentation must be re-checked before a production/release decision.

## Sources verified for M0

- Roshydromet WIS2 OGC API:
  http://wis2box.mecom.ru/oapi/collections/urn:wmo:md:ru-roshydromet:core.surface-based-observations.synop/items
- Roshydromet WIS2 node:
  http://wis2box.mecom.ru
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
