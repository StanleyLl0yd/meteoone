# Android-only architecture decision (2026-10-08)

Decision: **MeteoOne is a completely self-contained Android application.**
The M5 proprietary backend was cancelled by the product owner. No intermediate MeteoOne
HTTP service, hosted cache, provider gateway, Linux GRIB runtime, server-side verification,
cloud account or backend URL is part of the supported product.

## Production execution path

1. Android coarse-location capture normalizes to the canonical 0.1-degree grid.
2. Android M1 executes bounded direct HTTPS requests to public NOAA, ECMWF, DWD
   and Open-Meteo forecast sources, preserving provider vs model-family identity.
3. Embedded Android ecCodes/JNI decodes GRIB; the local forecast-domain engine
   canonicalizes, deduplicates same-family delivery paths and fuses hourly forecasts.
4. On-device M4 exact-run history, GHCNh observations, sample matching, aggregation
   and guarded model-family weighting run locally. Sparse/stale/conflicting evidence
   fails closed to the equal-weight baseline.
5. Room holds the only UI weather source of truth; DataStore persists only the
   privacy-reduced target. Failed refresh never wipes valid cached forecasts.
6. The installed Android app performs network requests only to third-party
   weather/observation providers, never through MeteoOne infrastructure.

## Scope removed

All `backend/*` modules, Android backend client and opt-in HTTP routing,
server-native Linux bundle/build workflow, server HTTP API, server verification
store/coordinator, obsolete server-only CI/test policies and backend documentation
are removed from current source and build graphs. The Android arm64 ecCodes/JNI
runtime, native AAR/release checks, Android Room migration and direct-provider
network limits remain in place.

The previously merged M5 commits are historical Git records only.
This decision does not undo completed M1-M4 or authorize starting M6.
