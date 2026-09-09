# MeteoOne Roadmap

The roadmap is milestone-driven. `main` should remain releasable after the Android project is bootstrapped.

## M0 — Foundation and research

- repository governance and security baseline;
- Android project bootstrap;
- canonical forecast domain model;
- provider/model separation;
- research harness for ECMWF, ICON, GFS, and MET Norway;
- initial 0–6 h, 6–24 h, 24–48 h, and 48–72 h backtests;
- architecture decision records;
- CI for build, tests, lint, dependency review, and security scanning.

Exit: reproducible project build and evidence-backed initial fusion strategy.

## M1 — Forecast core

- current-location acquisition with manual fallback;
- provider adapters and normalization;
- direct official-source adapters for NOAA/NCEP GFS, ECMWF IFS Open Data, and DWD ICON Open Data;
- retain Open-Meteo as a fallback, normalization, and cross-check provider path;
- preserve provider provenance separately from model-family identity so duplicate delivery paths never become duplicate fusion votes;
- provider fallback and cross-check orchestration;
- hourly forecast through 72 hours;
- robust fusion without ML;
- qualitative model agreement;
- graceful partial-provider failure;
- domain and mapper tests.

Exit: reliable forecast engine independent of UI.

## M2 — Offline-first data layer

- Room as the local source of truth;
- DataStore settings;
- cache freshness policy;
- stale-cache fallback;
- repository flows;
- provider rate-limit and retry policies.

Exit: app can start and remain useful without network access.

## M3 — Product UI

- onboarding and location permission flow;
- current conditions;
- hourly forecast;
- three-day summary;
- model comparison;
- settings and attribution;
- Russian and English localization;
- light/dark and adaptive layouts;
- accessibility baseline.

Exit: feature-complete pre-beta Android app.

## M4 — Verification engine

- persist forecast runs and lead times;
- ingest observations;
- temperature, pressure, wind, and precipitation metrics;
- model bias and skill by location/region, season, parameter, and lead time;
- evidence-backed dynamic weights.

Exit: fusion weights are measurable rather than purely heuristic.

## M5 — MeteoOne backend

- central provider gateway and cache;
- key isolation;
- deduplication and rate limiting;
- provider health;
- central orchestration of the direct official-source and fallback provider paths established earlier;
- server-side verification pipeline.

Exit: public clients no longer depend on embedding provider secrets.

## M6 — Beta hardening

- security and privacy audit;
- accessibility audit;
- performance and battery profiling;
- release R8 testing;
- 16 KB page-size verification where native code is present;
- device/API compatibility matrix;
- store metadata and privacy declarations.

Exit: release candidate quality.

## M7 — RuStore 1.0

- signed AAB as primary store artifact;
- signed APK as supplementary artifact;
- reproducible release workflow;
- checksum and mapping artifacts;
- release notes and final store review.

## M8 — Post-1.0

- calibrated numeric confidence;
- saved locations;
- alerts and nowcast;
- widgets and radar;
- additional model families and regional sources;
- adaptive regional/seasonal weighting.

## M9 — Google Play

- reuse the same application identity and app-signing lineage;
- Play App Signing onboarding;
- AAB production release;
- Data Safety and localized store listing.
