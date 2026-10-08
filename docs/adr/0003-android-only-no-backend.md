# ADR 0003: Android-only execution, no proprietary backend

Status: Accepted
Date: 2026-10-08

## Context

M1 forecast core, M2 offline-first data layer, M3 product UI and M4 local verification
engine were implemented as an installable standalone Android product. During M5, an
optional proprietary Linux/Ktor server, server-side provider orchestration and
verification, plus an Android HTTPS backend adapter were added. The product owner
explicitly rejected any MeteoOne-operated server component.

## Decision

MeteoOne runs as a single Android application. It directly retrieves forecast and
observation data from third-party providers, decodes native GRIB using its embedded
Android ecCodes/JNI runtime, merges independent model-family evidence on-device,
computes guarded M4 verification weights locally and persists forecasts in Room.

There is **no** MeteoOne HTTP backend, endpoint selection, cloud provider gateway,
self-hosted cache, server GRIB decoder, server verification coordinator, server
credential store or other MeteoOne-hosted prerequisite.

No precise device coordinates are persisted. Network failures preserve offline
Room forecast state. Missing/unsafe verification evidence falls back to equal weights.

## Consequences

- Remove active `backend/*` modules, Ktor dependency declarations, server workflow,
  HTTP client/endpoint, server-native build and server-only tests and documentation.
- Preserve all Android M1-M4 infrastructure, public provider HTTPS policy,
  native Android GRIB checks and local verification/history schema.
- Keep M5 Git commits solely as historical provenance, not supported runtime.
- Alpha APK and AAB must build without any backend URL or hosted service.
- No M6 implementation is authorized by this decision.

See `docs/architecture/ANDROID_ONLY_DECISION.md` for the production flow and
regression gates.
