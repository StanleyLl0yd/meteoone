# M2 Offline-First Data Boundary

M2 makes persistent local forecast state the application source of truth without changing the M1 provider, normalization, or fusion semantics.

## Persistence foundation

`:core:database` owns Room persistence only. It depends on `:core:model` and must not depend on provider execution, networking, location acquisition, or forecast-domain implementation modules.

Its public surface is deliberately small:

- `ForecastSnapshotDatabase.open(context)` creates the process-lifetime Room database composition object;
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

The next M2 layer is a forecast repository above `:core:database` and `:forecast:data`.

Its required direction is:

```text
M1 network/provider execution
        ↓ refresh
Forecast repository
        ↓ atomic write
Room / ForecastSnapshotStore
        ↓ Flow
Application presentation
```

A successful network refresh writes the fused forecast to Room. It does not directly emit an independent network result to consumers. Room invalidation is the publication path for updated forecast data.

A failed refresh must leave an existing cached forecast untouched and observable. Freshness/stale classification, retry/rate limiting, DataStore settings, and UI policy are separate later M2 slices layered above this persistence foundation.
