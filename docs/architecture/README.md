# Architecture

MeteoOne is built around a provider-independent forecast domain and a robust fusion engine.

## Current M1 flow

```text
Device location
    ↓
Forecast location normalization (:core:location)
    ↓
Provider/model execution (:forecast:data)
    ↓
Canonical normalization
    ↓
Forecast orchestration + Fusion Engine (:forecast:domain)
    ↓
M1 forecast result
    ↓
Android app shell
```

The current production path includes model-specific Open-Meteo 72-hour delivery plus bounded direct-official NOAA GFS, ECMWF IFS and DWD ICON cross-checks. Direct GRIB decode and spatial selection remain contained inside `:forecast:data`.

Room persistence, DataStore settings, stale-cache policy, repository flows, retry/rate-limit policy and an offline-first UI source of truth are **planned M2 responsibilities and are not part of the current M1 implementation**.

## Principles

### Model is not provider

A provider is a delivery system. A model is the underlying meteorological forecast family. Two providers exposing the same model must not be treated as independent ensemble votes.

The M1 direct-source transport and provenance boundary is documented in [`FORECAST_SOURCES.md`](FORECAST_SOURCES.md).

The M1 measured GRIB decoder capability gate is documented in [`GRIB_DECODER_REQUIREMENTS.md`](GRIB_DECODER_REQUIREMENTS.md).

The M1 DWD ICON run-scoped CLAT/CLON lifecycle is documented in [`DWD_ICON_GEOMETRY.md`](DWD_ICON_GEOMETRY.md).

The M1 Open-Meteo fallback and normalization path is documented in [`OPEN_METEO_FALLBACK.md`](OPEN_METEO_FALLBACK.md).

### Domain independence

Domain code must not depend on Android, Retrofit, Room, Compose, or provider DTOs. The fusion engine remains JVM-testable.

### Offline first

Offline-first persistence is the M2 target: persistent local state becomes the UI source of truth, network refresh updates persistence, and presentation observes repository flows. M1 deliberately stops before this layer.

### Graceful degradation

A provider failure must not make the application unusable when another valid forecast exists. Once M2 adds persistence, a usable cached forecast will extend this rule to offline/stale operation.

The M1 partial-provider coordination policy is documented in [`FORECAST_ORCHESTRATION.md`](FORECAST_ORCHESTRATION.md).

### Privacy by design

Exact location is transient. Forecast requests use a normalized location appropriate for weather-model resolution and future cache efficiency. Exact coordinates are never persisted.

The M1 foreground acquisition and normalization boundary is documented in [`LOCATION.md`](LOCATION.md).

### Explainability before ML

Version 1 uses robust statistical fusion. Machine learning is introduced only after a verification dataset exists and only with a deterministic fallback.

### Confidence requires calibration

Before sufficient verification data exists, the UI exposes qualitative model agreement rather than an arbitrary numeric confidence percentage.

## Implemented modules through M1

```text
:app
:core:model
:core:network
:core:location
:forecast:domain
:forecast:data
```

`:core:network` is the concrete JVM-testable bounded HTTPS execution boundary. It exposes only MeteoOne-owned request/result/cancellation types; OkHttp remains an implementation detail. `:forecast:data` owns production source execution, direct NOAA/ECMWF/DWD transport/GRIB decode/normalization, model-specific Open-Meteo delivery, and the UI-independent M1 composition root. `:core:location` owns foreground coarse-location acquisition and privacy-preserving forecast-coordinate normalization. `:forecast:domain` remains free of Android, HTTP, decoder and provider implementation details.

The app depends on `:forecast:data` for production M1 reachability but consumes only MeteoOne-owned APIs; native/ecCodes types remain internal to `:forecast:data`.

## Planned modules and responsibilities

The remaining roadmap modules are created only when their responsibilities become concrete:

```text
:core:database        # M2
:core:designsystem    # later UI milestone
:feature:forecast     # later UI milestone
:feature:models       # later UI milestone
:feature:settings     # later UI milestone
:feature:about        # later UI milestone
```

M2 additionally introduces DataStore/settings and repository/cache policy; those responsibilities do not require pre-creating empty modules in M1.

The exact split may be adjusted only when real dependency boundaries justify it.
