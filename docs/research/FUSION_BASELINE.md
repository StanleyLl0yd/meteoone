# M0 forecast fusion baseline

Status: Evidence-backed M0 baseline

The M0 engine remains deliberately simple and explainable. The initial production strategy is **equal weight per independent model family** after duplicate provider exposure is consolidated.

This decision is now backed by the August 2026 benchmark campaign rather than by a heuristic assumption.

## Independent evidence

Forecast provider and meteorological model family are separate concepts.

Multiple providers exposing the same known model family are consolidated into one evidence group before fusion. Unknown model families remain provider-specific until their provenance can be established.

MET Norway must not increase ECMWF's independent vote merely because it is a separate delivery path.

## Scalar parameters

For each timestamp:

1. consolidate duplicate provider exposure inside each evidence group using the median of available values;
2. combine evidence-group values with an equal-weight mean;
3. preserve missing values rather than inventing defaults.

M0 does not apply parameter-, location-, season-, or lead-specific learned weights.

## Measured M0 campaign

The final M0 campaign used:

- forecast initializations: 2026-08-01 through 2026-08-28;
- cycle: 00Z;
- horizon: 72 hours;
- locations: 10 representative Russian cities/climate regions;
- model families: ECMWF IFS, DWD ICON Global, NOAA GFS Global;
- forecast delivery path: archived Open-Meteo Single Runs, preserving model-family provenance;
- reference observations: direct Roshydromet WIS2 SYNOP;
- time stability checks: full period, first half, second half, odd initialization dates, even initialization dates;
- lead buckets: 0–6 h, 6–24 h, 24–48 h, 48–72 h.

The complete campaign contains 840 archived forecasts. Temperature and pressure have 6,714 usable comparisons per model family; wind has 6,711.

Pooled full-period error:

| Model family | Temperature MAE, °C | Pressure MAE, hPa | Wind vector error, m/s | 12 h precipitation MAE, mm |
| --- | ---: | ---: | ---: | ---: |
| DWD ICON | 1.211 | 0.764 | **1.750** | 1.337 |
| ECMWF IFS | **1.203** | **0.636** | 1.905 | **1.242** |
| NOAA GFS | 1.508 | 0.755 | 2.304 | 1.287 |

The pooled table is not used alone to choose weights.

### Stability evidence

Across five time splits and four lead buckets:

- pressure: ECMWF IFS wins 20/20 available split × lead cells;
- wind: DWD ICON wins 20/20;
- temperature: DWD ICON wins 10 and ECMWF IFS wins 10.

Across 10 locations and four lead buckets:

- pressure: ECMWF IFS 29 wins, NOAA GFS 7, DWD ICON 4;
- wind: DWD ICON 26 wins, ECMWF IFS 14;
- temperature: DWD ICON 23 wins, ECMWF IFS 14, NOAA GFS 3.

Precipitation is scored only where the forecast and observation intervals are exactly comparable. Roshydromet supplied 12-hour accumulation intervals; Open-Meteo documents hourly precipitation as a preceding-hour sum. The scorer therefore sums exactly the 12 preceding-hour values and rejects partial or misaligned intervals. No synthetic hourly truth is created.

This produces 1,599 usable 12-hour precipitation comparisons per model family. There is no valid 0–6 h precipitation cell because a complete 12-hour observation interval cannot fit in that lead bucket.

Across the 15 available precipitation time-split × lead cells:

- ECMWF IFS wins 9;
- NOAA GFS wins 5;
- DWD ICON wins 1.

Across the 30 available precipitation location × lead cells:

- ECMWF IFS wins 11;
- NOAA GFS wins 11;
- DWD ICON wins 8.

### Weight decision

**Keep equal model-family weights for M0.**

The evidence does not support one global model ordering:

- ECMWF IFS is materially strongest for pressure;
- DWD ICON is materially strongest for wind;
- ECMWF IFS and DWD ICON are effectively tied in pooled temperature skill and exchange wins across stability partitions;
- precipitation rankings vary materially by time split and geography;
- NOAA GFS is weaker for temperature and wind but remains competitive for pressure and precipitation.

A single unequal model weight would therefore improve some parameters by construction while degrading the evidence basis for others.

The campaign is also one month in one season, and the planned M1 direct official-source adapters will change the delivery pipeline from the Open-Meteo benchmark path. M0 therefore does not convert these measured errors into arbitrary numeric weight ratios.

Parameter-, region-, season-, and lead-specific adaptive weights belong to the verification work in M4 after broader out-of-sample evidence exists.

## Precipitation probability

The deterministic ECMWF IFS, DWD ICON and NOAA GFS benchmark runs intentionally exclude Open-Meteo `precipitation_probability`.

Open-Meteo may derive probability fields from related ensemble products such as ICON-EPS or GEFS. Those probabilities must not be attributed to the deterministic run without explicit probabilistic provenance.

The Brier implementation remains available for a future explicitly modelled probabilistic source.

## Wind direction

Directions use circular averaging so values around north, such as 350° and 10°, combine near 0° rather than 180°.

## Conditions

Weather condition codes are not fused in M0. The fused condition remains `UNKNOWN` until a canonical condition resolver is defined from normalized weather parameters.

## Model agreement

Temperature spread across independent evidence groups produces a qualitative diagnostic:

- high: spread <= 1.5 °C;
- medium: spread <= 3.0 °C;
- low: spread > 3.0 °C;
- insufficient: fewer than two independent temperature signals.

These thresholds are an initial diagnostic only. They are not calibrated forecast probabilities and must not be presented as numeric confidence.

## Reproducibility

The network campaign evidence was produced by GitHub Actions run `34353729773`.

Exact precipitation interval rescoring reused that immutable campaign artifact and was verified by run `34367231365`. The rescored artifact digest is:

`sha256:8698858548f6961deaf023a696918d74d5f88c88fbb125f9e26c9834e1961b25`

Bulk generated research output is not committed to the repository. The repository-owned collector, observation parser, scorer, aggregation, stability analysis, fixtures, and tests are the durable reproducibility surface.
