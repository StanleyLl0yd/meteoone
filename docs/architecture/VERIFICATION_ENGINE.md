# Verification Engine

Status: M4 in progress.

MeteoOne M4 measures forecast skill before it changes production fusion weights. The verification path is deliberately separate from provider transport and from the latest offline forecast cache.

## Data flow

```text
historical SourceForecast evidence
        +
surface observations
        ↓
canonical verification matching
        ↓
per-sample errors
        ↓
skill/bias aggregates
(location/region × season × parameter × lead)
        ↓
guarded model-family weights
        ↓
Forecast Fusion Engine
```

The fusion engine must keep the existing equal-weight behavior whenever the verification layer cannot provide sufficient, stable evidence.

## Domain boundary

`:verification:domain` is a pure JVM module. It depends only on MeteoOne-owned `:core:model` types and owns:

- verification parameters;
- the 0–6 h, 6–24 h, 24–48 h and 48–72 h lead buckets established by M0;
- meteorological seasons;
- canonical public observation-station metadata;
- canonical surface and precipitation observations;
- verification sample identity/provenance;
- scalar and wind-vector error primitives;
- exact-interval precipitation comparison.

It does not depend on Android, Room, HTTP, WIS2/GHCNh formats or provider DTOs.

`:verification:data` is the pure JVM transport/normalization boundary for M4 evidence sources. It owns bounded NOAA/NCEI GHCNh station discovery plus station/year PSV retrieval, format validation, source/QC preservation, candidate fallback and normalization into MeteoOne-owned observation types.

## Identity and privacy

Every verification context retains both provider pipeline and model-family identity. Provider paths may be diagnosed independently, but multiple providers carrying one model family must never multiply that model family's fusion weight.

Verification persistence uses only the existing privacy-reduced `ForecastCoordinate`. Exact device coordinates remain transient and must never be written into verification history.

Public observing-station coordinates are not user-location data, but station selection must still be performed against the privacy-reduced forecast coordinate in production M4.

## Observation transport

The M0 Roshydromet adapter is a research exception that uses a measured HTTP-only endpoint. Production Android M4 must not reuse that transport.

Production observation ingestion uses HTTPS and real station measurements only. NOAA/NCEI GHCNh is the primary historical/on-demand source because it is the current hourly/synoptic station dataset replacing ISD and exposes station/year archives suitable for catch-up after the app has been offline. WMO WIS2 core observations remain a possible supplementary fresh path, but Global Cache retention is too short to be the only verification archive for an intermittently used Android app. Transport/parsing stays outside the verification domain.

GHCNh station/year requests are bound to the exact NCEI HTTPS host, station id and year and inherit the common bounded-response/no-redirect transport. PSV parsing is driven by normalized header names rather than column positions. Current GHCNh v1.1 data identify rows with `Station_ID`, an ISO UTC date-time and named latitude/longitude/elevation fields; the parser also accepts the NCEI search aliases `STATION` and `DATE` without changing identity semantics.

For every retained weather value the data layer preserves the five GHCNh attributes: measurement code, quality code, report type, source code and source station id. Canonical M4 observations accept only values with no failure flag or the legacy documented pass-all-QC codes `1`/`5`; suspect/error flags remain raw evidence but do not become verification truth. Duplicate timestamps are resolved by usable-field completeness, while equal-quality/equal-completeness conflicts become missing instead of being guessed.

Generic `precipitation` remains raw evidence because its accumulation interval is not intrinsically fixed. Canonical precipitation is emitted only from explicit-duration GHCNh fields (5/15 minutes and 3/6/9/12/15/18/21/24 hours). Trace reports are preserved as source evidence but are not fabricated as 0 mm.

## Matching semantics

M4 normalizes observation input order and matches each instantaneous forecast valid time to the nearest surface observation within 30 minutes. The 30-minute bound is a hard production maximum inherited from the M0 scorer; callers may narrow it but cannot broaden it. Equal-distance ties choose the earlier observation deterministically.

Matching is parameter-by-parameter. Missing forecast or observed values produce no sample. Calm wind remains the zero vector and does not require a direction; non-calm wind without direction remains unusable rather than receiving a fabricated bearing.

Precipitation does not use nearest-time matching. A sample is emitted only when one forecast precipitation interval exactly equals one observed interval. M4 does not sum partial forecast intervals, interpolate observations or manufacture hourly precipitation truth.

Every emitted sample retains the privacy-safe forecast coordinate, provider delivery path, model family, exact model run, valid time, lead bucket, local meteorological season, observation station and observation timestamp/interval. Re-running the matcher over identical retained evidence therefore produces the same ordered sample set.

## Metric semantics

Temperature and sea-level pressure use signed error, absolute error and squared error.

Wind uses meteorological vector components. Calm wind is the zero vector and does not require a fabricated direction. Non-calm wind without direction is not a usable vector sample.

Precipitation is compared only when forecast and observation intervals are exactly compatible. Partial coverage, interpolation and synthetic hourly truth are forbidden.

Missing values produce no sample. They are never replaced by zero.

## Skill aggregation

Per-sample evidence is aggregated twice: at the exact privacy-reduced 0.1° forecast coordinate and at a deterministic 5°×5° coarse grid. The coarse grid uses no administrative or national boundaries; latitude bands are anchored at -90° and longitude bands at -180°, with the north-pole value assigned to the final 85°..90° band.

Provider-path samples remain available as diagnostics, but model-family skill collapses duplicate delivery paths for the same model-family/run/valid-time/observation identity into one independent sample. Scalar duplicate paths use the median signed error. Wind duplicate paths use component-wise median vector error. This mirrors the M0 rule that multiple deliveries of one model family do not create additional model votes.

Each skill cell is keyed by scope, meteorological season, parameter, canonical lead bucket and model family. It carries independent sample count, distinct model-run count, distinct coordinate count, valid-time coverage and observation-time coverage so the later weight policy can enforce explicit sample and staleness gates. Temperature, pressure and compatible precipitation retain bias/MAE/RMSE; wind retains mean u/v component error and mean vector-error magnitude. Provider diagnostics carry the same coverage and metric family but never increase the model-family independent sample count.

## Weight safety

M4 must not convert one small campaign or one pooled winner directly into production weights. A later weight-policy slice must make sample counts, evidence scope, recency, stability/materiality thresholds and fallback behavior explicit.

Until those gates pass, the evidence-backed result is the existing equal model-family weighting.
