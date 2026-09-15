# Architecture

MeteoOne is built around a provider-independent forecast domain and a robust fusion engine.

## Current M2 flow

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
Offline-first repository (:forecast:repository)
    ↓
Room snapshot store (:core:database)
    ↓
Observable local forecast state
    ↓
Android app
```

The production forecast path includes model-specific Open-Meteo 72-hour delivery plus bounded direct-official NOAA GFS, ECMWF IFS and DWD ICON cross-checks. Direct GRIB decode and spatial selection remain contained inside `:forecast:data`.

M2 now has a Room persistence foundation plus a repository source-of-truth layer. Complete fused forecasts are stored under privacy-reduced `ForecastCoordinate` keys, and application forecast access is routed through `:forecast:repository`. Freshness/stale policy, DataStore settings, retry/rate-limit policy and forecast UI remain subsequent M2 slices.

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

Persistent local forecast state is the M2 source of truth. Network refresh updates persistence and presentation observes repository flows backed by Room. Network results are not a second direct presentation data source.

The M2 persistence and repository boundary is documented in [`OFFLINE_DATA.md`](OFFLINE_DATA.md).

### Graceful degradation

A provider failure must not make the application unusable when another valid forecast exists. M2 extends this rule across network loss: failed refreshes must not erase a usable cached forecast.

The M1 partial-provider coordination policy is documented in [`FORECAST_ORCHESTRATION.md`](FORECAST_ORCHESTRATION.md).

### Privacy by design

Exact location is transient. Forecast requests and persistent cache identity use a normalized location appropriate for weather-model resolution and cache efficiency. Exact coordinates are never persisted.

The M1 foreground acquisition and normalization boundary is documented in [`LOCATION.md`](LOCATION.md).

### Explainability before ML

Version 1 uses robust statistical fusion. Machine learning is introduced only after a verification dataset exists and only with a deterministic fallback.

### Confidence requires calibration

Before sufficient verification data exists, the UI exposes qualitative model agreement rather than an arbitrary numeric confidence percentage.

## Implemented modules through the current M2 slice

```text
:app
:core:model
:core:network
:core:location
:core:database
:forecast:domain
:forecast:data
:forecast:repository
```

`:core:network` is the concrete JVM-testable bounded HTTPS execution boundary. It exposes only MeteoOne-owned request/result/cancellation types; OkHttp remains an implementation detail. `:forecast:data` owns production source execution, direct NOAA/ECMWF/DWD transport/GRIB decode/normalization, model-specific Open-Meteo delivery, and the UI-independent M1 execution façade. `:core:location` owns foreground coarse-location acquisition and privacy-preserving forecast-coordinate normalization. `:forecast:domain` remains free of Android, HTTP, decoder and provider implementation details.

`:core:database` owns only Room forecast persistence. It exposes MeteoOne model types through `ForecastSnapshotStore`, stores coordinate identity as integer tenths of a degree, and has a policy-enforced dependency boundary that prevents it from depending on location acquisition, networking, forecast execution, or forecast-domain implementation modules.

`:forecast:repository` owns offline-first composition between M1 execution and Room. Its observable API is backed only by the snapshot store; refresh results report update/degradation/failure state separately and never expose provider, GRIB, Room, or network implementation types. The app depends on this layer rather than `:forecast:data` directly.

## Planned modules and responsibilities

The remaining roadmap modules are created only when their responsibilities become concrete:

```text
:core:designsystem    # later UI milestone
:feature:forecast     # later UI milestone
:feature:models       # later UI milestone
:feature:settings     # later UI milestone
:feature:about        # later UI milestone
```

M2 additionally introduces freshness/stale policy, DataStore-backed settings/target state, bounded retry/rate-limit behavior, and an offline-first forecast presentation path. Those responsibilities are added as focused slices rather than pre-created empty modules.

The exact split may be adjusted only when real dependency boundaries justify it.
