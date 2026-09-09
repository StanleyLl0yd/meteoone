# Architecture

MeteoOne is built around a provider-independent forecast domain and a robust fusion engine.

## Core flow

```text
Device location
    ↓
Forecast location normalization
    ↓
Provider/model adapters
    ↓
Canonical normalization
    ↓
Forecast Fusion Engine
    ↓
Local database
    ↓
Repository Flow
    ↓
Compose UI
```

## Principles

### Model is not provider

A provider is a delivery system. A model is the underlying meteorological forecast family. Two providers exposing the same model must not be treated as independent ensemble votes.

### Domain independence

Domain code must not depend on Android, Retrofit, Room, Compose, or provider DTOs. The fusion engine should remain JVM-testable.

### Offline first

Persistent local state is the UI source of truth. Network refresh updates persistence; presentation observes persistence through flows.

### Graceful degradation

A provider failure must not make the application unusable when another valid forecast or a usable cached forecast exists.

### Privacy by design

Exact location is transient. Forecast requests use a normalized location appropriate for weather-model resolution and cache efficiency. Exact coordinates are never persisted.

### Explainability before ML

Version 1 uses robust statistical fusion. Machine learning is introduced only after a verification dataset exists and only with a deterministic fallback.

### Confidence requires calibration

Before sufficient verification data exists, the UI exposes qualitative model agreement rather than an arbitrary numeric confidence percentage.

## Planned Android modules

```text
:app
:core:model
:core:network
:core:database
:core:location
:core:designsystem
:forecast:domain
:forecast:data
:feature:forecast
:feature:models
:feature:settings
:feature:about
```

The exact split may be adjusted only when real dependency boundaries justify it.
