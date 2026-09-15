# M2 Offline-First Data Boundary

M2 makes persistent local forecast state the application source of truth without changing the M1 provider, normalization, or fusion semantics.

## Persistence foundation

`:core:database` owns Room persistence only. It depends on `:core:model` and must not depend on provider execution, networking, location acquisition, or forecast-domain implementation modules.

Its public surface is deliberately small:

- `ForecastSnapshotDatabase.open(context)` creates the process-lifetime Room-backed store;
- `ForecastSnapshotStore.observe(coordinate)` exposes the persisted forecast as a `Flow<FusedForecast?>`;
- `ForecastSnapshotStore.read(coordinate)` performs a point read;
- `ForecastSnapshotStore.replace(coordinate, forecast)` atomically replaces one coordinate snapshot.

Room entities, DAOs, database internals, and persistence mappers remain implementation details.

## Privacy-safe cache identity

The persistence API accepts only `ForecastCoordinate`, which has already crossed the M1 privacy-normalization boundary. Exact device location must never be supplied to or persisted by `:core:database`.

SQLite stores the normalized coordinate as signed integer tenths of a degree. For example:

```text
59.9, 30.3 -> 599:303
```

The stored forecast location must exactly match that normalized coordinate. A forecast containing more precise or otherwise different latitude/longitude values is rejected before persistence.

## Atomic snapshots

A cached forecast is one logical snapshot consisting of:

- one snapshot header containing coordinate identity, generation timestamp, elevation, and time-zone metadata;
- ordered hourly forecast rows containing the complete canonical `FusedForecast` payload.

Replacement is transactional. Existing rows for a coordinate are removed and the new header plus all hourly rows are inserted in one Room transaction. Observers therefore must not see a partially replaced forecast.

Hourly rows reference the snapshot through a foreign key with `ON DELETE CASCADE`. Reconstruction verifies contiguous positions, coordinate-key consistency, timestamp nanos, enum values, intervals, and canonical model invariants. Corrupt persisted state fails closed instead of being silently normalized.

## Schema lifecycle

Room schema export is enabled and the generated schema is committed under:

```text
core/database/schemas/
```

CI rebuilds the database module and fails if generated schema output differs from the committed files or creates an untracked schema. Any future database-version change therefore requires an explicit schema update and migration decision.

The initial schema is database version 1.

## Repository source of truth

`:forecast:repository` sits above `:core:database` and `:forecast:data`. The Android app depends on this repository rather than reaching the M1 execution layer directly.

The direction is:

```text
M1 network/provider execution
        ↓ refresh
:forecast:repository
        ↓ atomic write
Room / ForecastSnapshotStore
        ↓ Flow
Application presentation
```

There is no parallel in-memory/network forecast stream inside the repository. `ForecastRepository.observe(coordinate)` derives presentation state only from the Room-backed snapshot flow.

A successful refresh executes M1 off the caller thread, persists the fused forecast, and returns a refresh outcome only after persistence succeeds. Room invalidation is the publication path for the new forecast. The refresh outcome distinguishes a fully successful update from an update where one or more M1 sources degraded, without exposing provider or GRIB implementation types.

`Unavailable`, unexpected source failure, native linkage failure, or persistence failure does not delete or replace an existing cached forecast. Coroutine cancellation is propagated rather than converted into an ordinary refresh failure.

Refresh execution is serialized by the repository. This intentionally favors a simple bounded first-alpha contract over overlapping expensive six-source M1 executions. A later policy may relax serialization only with explicit per-target/request pacing rules.

## Freshness and stale fallback

Repository observation wraps each persisted forecast in `ForecastCacheState` with a deterministic `ForecastFreshness` classification:

- `FRESH`: generation age is strictly less than 3 hours and the forecast horizon has not ended;
- `STALE`: generation age is at least 3 hours while the final hourly forecast timestamp is still current or future;
- `EXPIRED`: current time is later than the final hourly forecast timestamp.

`shouldRefresh` is false only for `FRESH`; it is true for `STALE` and `EXPIRED`.

If the device clock is earlier than the snapshot `generatedAt`, freshness uses zero generation age rather than treating clock skew as a persistence failure. Horizon expiry still uses the actual current time and the final forecast timestamp.

Age never deletes cached data. Stale and expired forecasts remain observable so an offline caller can present the last known forecast together with its age state instead of collapsing to an empty screen.

Freshness uses an injected `Clock` and has no background timer in this slice. A new observer evaluates freshness immediately. Completion of any explicit refresh attempt also increments an internal re-evaluation signal; that signal contains no forecast payload and only reclassifies the forecast already supplied by Room. Therefore a failed refresh can move an actively observed cached snapshot from `FRESH` to `STALE` without introducing a second source of forecast data.

## Persisted active target

Room forecast payloads are keyed by privacy-reduced coordinate, so a cold-started offline process also needs a durable identifier telling it which cached coordinate to reopen. `ForecastTarget` provides that identity together with optional elevation and time-zone metadata.

`:core:preferences` owns this small state in AndroidX Preferences DataStore. Its public API accepts and emits only `ForecastTarget`; DataStore keys and preference objects remain implementation details. The module depends on `:core:model` and DataStore only and does not depend on Room, network, location acquisition, provider execution, or the forecast repository.

Latitude and longitude are persisted only as signed integer tenths of a degree, matching Room cache identity. Exact/raw device precision is therefore structurally excluded from this persistence boundary. Set and clear operations each use one atomic DataStore edit.

Incomplete, invalid, blank-time-zone, or wrong-type preference combinations decode to no active target. Physical Preferences-file corruption is recovered by replacing the corrupted payload with empty preferences, also yielding no active target rather than fabricated location state.

The detailed target contract is documented in [`FORECAST_TARGET.md`](FORECAST_TARGET.md).

## Bounded provider request policy

M2 applies retry and provider-declared pacing inside `:forecast:data`, at the HTTP adapter boundary where transport failure reasons and exact request metadata are still available. The generic `:core:network` HTTPS transport remains retry-free.

Every forecast HTTP attempt is validated before the retry policy sees its result. A request may be attempted at most twice total, and the second attempt is allowed only after `BoundedHttpsFailureReason.IO`. Cancellation, oversized responses, invalid HTTP status/range/header responses, parsing or provenance failures, decompression failures, and native/GRIB failures are never retried.

A retry creates a fresh underlying one-shot HTTP call. The default retry delay is one second. If an official request declares a longer `minimumRequestSpacing`, the stricter interval wins; NOAA NOMADS currently declares ten seconds. Request-start pacing is process-wide per host so constructing another M1 engine instance cannot bypass the spacing contract.

Cancellation wakes a request waiting for a paced/retry start, cancels an active underlying call, and prevents a later retry attempt from being created. A cancelled reservation may conservatively leave a later host slot reserved; it can delay a later request but cannot make provider traffic less bounded.

Provider-health aggregation/circuit breaking is not part of this client-side M2 policy. `ROADMAP.md` places central provider health in M5 together with the MeteoOne backend.

App target/repository composition and the minimal forecast UI remain the final M2/alpha vertical slice.
