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

## Metric semantics

Temperature and sea-level pressure use signed error, absolute error and squared error.

Wind uses meteorological vector components. Calm wind is the zero vector and does not require a fabricated direction. Non-calm wind without direction is not a usable vector sample.

Precipitation is compared only when forecast and observation intervals are exactly compatible. Partial coverage, interpolation and synthetic hourly truth are forbidden.

Missing values produce no sample. They are never replaced by zero.

## Weight safety

M4 must not convert one small campaign or one pooled winner directly into production weights. A later weight-policy slice must make sample counts, evidence scope, recency, stability/materiality thresholds and fallback behavior explicit.

Until those gates pass, the evidence-backed result is the existing equal model-family weighting.
