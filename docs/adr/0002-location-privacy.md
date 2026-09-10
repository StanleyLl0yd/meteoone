# ADR-0002: Do not persist exact device location

Status: Accepted

## Context

Weather models operate on grids far coarser than GPS precision. Persisting exact coordinates would add privacy risk without proportional forecast value.

## Decision

Exact device coordinates may exist transiently in memory only while resolving the current forecast location. Network/cache coordinates are normalized to an appropriate forecast grid or rounded location.

For the M1 foreground current-location path, MeteoOne requests only `ACCESS_COARSE_LOCATION`. Fine/precise and background location permissions are not required by the current product scope. A future need for either requires a new privacy/security review before adoption.

Exact coordinates must not be written to Room, logs, analytics, crash reports, test fixtures, or issue attachments.

Background location tracking is outside the initial product scope.

## Consequences

- Cache reuse improves when device readings drift slightly.
- Stored data has lower privacy sensitivity.
- Current-location acquisition can fail cleanly into the manual-location fallback when coarse location is unavailable.
- Future features requiring precise or persistent location need a new architecture/security review.
