# ADR-0002: Do not persist exact device location

Status: Accepted

## Context

Weather models operate on grids far coarser than GPS precision. Persisting exact coordinates would add privacy risk without proportional forecast value.

## Decision

Exact device coordinates may exist transiently in memory only while resolving the current forecast location. Network/cache coordinates are normalized to an appropriate forecast grid or rounded location.

Exact coordinates must not be written to Room, logs, analytics, crash reports, test fixtures, or issue attachments.

Background location tracking is outside the initial product scope.

## Consequences

- Cache reuse improves when GPS readings drift slightly.
- Stored data has lower privacy sensitivity.
- Future features requiring precise persistent location need a new architecture/security review.
