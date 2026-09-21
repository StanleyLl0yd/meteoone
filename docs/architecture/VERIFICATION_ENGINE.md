# Verification Engine

Status: M4 production-composition exit verification in progress.

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

M4 does not convert one small campaign or one pooled winner directly into production weights. The guarded policy remains parameter- and lead-specific and tries evidence in this order:

1. exact privacy-reduced coordinate + meteorological season;
2. the containing 5°×5° region + meteorological season;
3. equal model-family weights.

The default activation floors are deliberately conservative safety gates rather than claims of statistical optimality: at least 120 independent samples and 14 distinct model runs per model family; regional evidence also requires at least three distinct privacy-reduced coordinates. Evidence older than 30 days is stale. For temporal stability, the ordered run set is split into chronological halves and each half must retain at least 40 samples and five runs per model family.

Unequal weights require the same unique model-family winner in the full window and both chronological halves, with at least a 5% skill advantage over the runner-up in every comparison. Temperature, pressure and precipitation use MAE; wind uses mean vector-error magnitude. Missing, sparse, stale, geographically narrow, immaterial or unstable evidence returns equal weights.

When all gates pass, weights are derived monotonically from measured skill and normalized to mean 1.0. The strongest-to-weakest ratio is capped at 1.5×, so short-term evidence cannot suppress a model. These bounds are safety policy, not calibrated probability or numeric confidence. Every measured decision exposes its scope, full/half scores, sample/run counts, latest observation time, winning model family, material margin and ratio bound.

Provider delivery paths never receive independent weights. #190 collapses duplicate paths inside one model-family/run/valid-time/observation identity before the policy sees independent skill; provider-path metrics remain diagnostic only.

Until every applicable gate passes, the evidence-backed result remains the existing M0 equal model-family weighting.
## Fusion integration

The forecast domain owns a small verification-agnostic model-family weight-provider boundary. `:forecast:domain` does not depend on Room, observation transport or verification storage. `:forecast:data` adapts the guarded M4 policy to that boundary by mapping only the four verified parameters (temperature, sea-level pressure, wind and precipitation) plus exact model-run-derived lead, local meteorological season and the privacy-reduced forecast coordinate.

Provider paths still collapse before model-family weighting. The equal baseline is unchanged and may therefore use all valid delivery paths for that model family. The measured candidate is stricter: only provider paths that expose one common exact non-null model run may contribute its weighted value. A null-run Open-Meteo delivery is never assigned the run exposed by a direct official path and is never folded into that exact-run measured value.

At least two model families must expose measured candidates for the same exact run before the policy is queried. Families without trustworthy exact-run provenance remain present in the final fusion with their legacy collapsed value and neutral weight 1.0; they are not included in the measured-policy request. UNKNOWN model families are likewise never dynamically weighted. Conflicting exact runs cause equal fallback rather than run selection or inference.

The integration is fail-safe. `EqualFallback`, missing or conflicting run provenance, unsupported parameters, malformed/mismatched measured weights, or an evidence-provider failure all execute the existing equal-weight fusion path. When weights are equal, scalar averaging and circular wind-direction behavior are not replaced by a numerically different implementation.

Temperature and sea-level pressure use guarded weighted model-family scalars. Precipitation first runs the existing exact interval-selection rule and only then weights values from the selected interval. Wind uses measured model-family weights on meteorological vectors; if complete vectors or trustworthy run provenance are unavailable it retains the established equal speed/direction behavior. Wind gusts and all other fields remain equal-weight because M4 has no verified skill metric for them.

`:forecast:data` exposes an explicit Android composition overload that accepts already-collected `VerificationWeightSampleSource` evidence. Fusion itself performs no observation/history I/O; acquisition, persistence and matching stay in their existing M4 capabilities. The ordinary `M1ForecastEngine.android(context)` factory remains the equal-weight path when no evidence source is supplied, while the Android repository composes the M4 evidence lifecycle around the production refresh delegate.

## Production evidence lifecycle

`:forecast:repository` is the production I/O composition boundary for M4. Before an ordinary forecast refresh it prepares only bounded local verification evidence, exposes that evidence through a refresh-scoped sample source while the delegate forecast runs, and clears the sample source in `finally` so evidence cannot leak into a later refresh.

The coordinator works on a 30-day verification window and targets 14 complete exact 00Z model runs. A refresh acquires at most two missing eligible exact runs while bootstrapping; once the target is present, it may still acquire the newest missing eligible run as history advances. Exact-run acquisition remains bounded by the existing M4 source and persistence contracts.

Persisted public GHCNh stations are reused and re-ranked against the privacy-reduced forecast coordinate before any station-catalog request. Stored observations are bounded to the current evaluation time, so future-dated rows cannot satisfy freshness. When new exact runs are being acquired, observation evidence older than seven days is refreshed; absent usable stored evidence also triggers bounded acquisition. Newly acquired observations are archived through the existing Room v4 observation store without a schema migration.

Samples are produced from retained exact-run history plus the selected stored observation series and exist only for the current refresh. Any non-cancellation verification acquisition, storage, matching or policy-preparation failure degrades to empty evidence, which preserves the established equal-weight fusion behavior. Coroutine cancellation is always propagated.

This lifecycle is opportunistic foreground repository composition only. M4 adds no backend, central scheduler, provider-health service, background orchestration or other M5 capability.

Measured weights therefore change only fields and hours for which both current forecast provenance and historical M4 evidence satisfy the full safety contract. Everywhere else fusion is deterministically identical to the M0/M1 equal-weight baseline.
