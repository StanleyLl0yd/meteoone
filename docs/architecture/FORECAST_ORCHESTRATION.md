# Forecast orchestration

M1 keeps provider-attempt coordination in `:forecast:domain`, above provider transport and below presentation/persistence concerns.

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

## M1 boundary

This layer does not execute HTTP requests and does not define coroutine, cancellation, retry/backoff, rate-limit, provider-health, persistence, cache, stale-data, or UI policy. Those concerns remain outside this M1 domain boundary or belong to later milestones.
