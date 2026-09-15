# Forecast orchestration

M1 keeps provider-result coordination in `:forecast:domain`, above provider transport and below presentation/persistence concerns. Production source execution and the UI-independent M1 composition root live in `:forecast:data`; the domain layer remains free of Android, HTTP, decoder, and provider implementation details.

## Attempt identity

Each attempted delivery path is identified by the pair `(ForecastProvider, ModelFamily)`. The pair identifies one concrete provider/model attempt for an orchestration cycle.

An orchestration input set must be non-empty, and duplicate identities are rejected. This prevents one delivery path from being represented more than once.

## Results

Each attempt produces one domain result:

- `ForecastSourceResult.Success` contains a canonical `SourceForecast`; its identity is derived from forecast provenance.
- `ForecastSourceResult.Failure` preserves only the provider/model identity. Provider-specific exceptions and transport details remain outside the domain.

When at least one source succeeds, `ForecastSourceOrchestrator` passes every successful `SourceForecast` to the existing `ForecastFusionEngine` and returns `ForecastOrchestrationResult.Available`. The outcome also retains the successful and failed source identities for degradation diagnostics.

When every attempted source fails, orchestration returns `ForecastOrchestrationResult.Unavailable`; it does not fabricate a forecast.

## Model-family independence

Provider identity and model-family identity are intentionally separate. NOAA GFS delivered directly by NOAA and NOAA GFS delivered by Open-Meteo are valid alternate delivery paths and may both succeed in one orchestration cycle.

They are not independent meteorological votes. `ForecastFusionEngine` remains the authority for family-level de-duplication, so alternate providers for the same `ModelFamily` contribute one independent fusion vote while still remaining visible as distinct provider paths.

## Categorical weather conditions

Canonical `WeatherCondition` values use the same independent-evidence grouping as scalar fusion. `UNKNOWN` represents missing or unresolved categorical evidence and does not vote.

Within one model-family evidence group, one distinct non-`UNKNOWN` condition becomes that family's categorical vote. If alternate provider deliveries for the same family disagree on non-`UNKNOWN` conditions, that family is unresolved for the condition field rather than receiving multiple provider votes.

Across resolved independent evidence groups, the fusion engine selects a condition only when one category has a unique plurality. A categorical tie remains `WeatherCondition.UNKNOWN`; MeteoOne does not invent a severity ordering or arbitrary weather-state tie-break before measured evidence justifies one.

## M1 production composition

The public `M1ForecastEngine` forecast entry point accepts an already privacy-normalized `ForecastCoordinate` together with elevation and time-zone metadata. It constructs the canonical `ForecastLocation` retained by source forecasts from that coordinate; arbitrary/raw latitude and longitude values are not public inputs to `:forecast:data`. The engine performs one bounded direct-official cross-check for NOAA GFS, ECMWF IFS and DWD ICON, together with the three exact 72-hour model-specific Open-Meteo delivery paths.

A successful Open-Meteo path is required to establish the complete 72-point hourly M1 horizon. The Open-Meteo request is bound to the same injected generation time used by orchestration: its absolute UTC `start_hour` is the first exact hour at or after that instant, and its inclusive `end_hour` is 71 hours later. Server-relative `forecast_hours` is not used because it would make the baseline depend on Open-Meteo's request-processing clock rather than MeteoOne's orchestration provenance. The response must match those exact first and last timestamps as well as the 72-point hourly cadence.

Direct-official cross-checks are deliberately sparse and cannot by themselves turn an incomplete point/field sample into a complete forecast. All successful source forecasts are restricted to the same validated 72-hour timestamp window before the existing domain orchestrator and fusion engine are invoked.

Source attempts fail independently. Transport, decode, native linkage and validation failures are reduced to the corresponding provider/model `ForecastSourceResult.Failure`; another valid 72-hour source can still produce an available forecast. Direct and Open-Meteo delivery of the same model family remain separate provider paths but one independent meteorological evidence group.

The direct run policy selects a conservative already-published 00/06/12/18 UTC operational cycle from the injected generation time. NOAA and DWD cross-check the next hourly valid step; ECMWF is aligned to the next supported three-hour direct step and is never represented as direct hourly IFS.

## M1 public data boundary

The consumer-facing `:forecast:data` surface is the `M1ForecastEngine` façade and `M1ForecastEngineResult`. Provider request planners and DTOs, HTTP adaptation, Open-Meteo mapping, direct-source parsing, GRIB decode values and semantics, native/ecCodes integration, decompression and provider-grid selection are module implementation details and are not supported consumer APIs.

The Android `:app` module declares its production forecast dependency on `:forecast:data`, not directly on `:forecast:domain`. `:forecast:data` currently exports `:forecast:domain` because the public M1 result contract includes domain fusion/diagnostic types; that transitive type exposure does not make the app responsible for production orchestration or provider composition.

The domain orchestration layer itself still does not execute HTTP requests. Production execution is synchronous inside `:forecast:data`; Android callers must invoke it off the main thread. M1 does not define retry/backoff, request pacing or sleep, provider-health state, persistence, Room, DataStore, cache, stale-data, repository-flow, or UI policy. Those concerns belong to M2 or later milestones.
