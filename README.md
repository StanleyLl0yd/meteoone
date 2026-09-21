# MeteoOne

**Many models. One forecast.**

MeteoOne is an Android weather application that combines forecasts from multiple weather models and providers into one local forecast for the user's current location.

## Product principles

- One clear forecast first; model/source comparison on demand.
- Model and provider are separate concepts.
- Forecast fusion is robust and explainable before any ML is introduced.
- Exact user location is not persisted.
- Offline-first: cached forecast is shown immediately and explicit refresh never hides usable cached data.
- Partial provider failure must degrade gracefully.
- Confidence is not shown as a calibrated percentage until it is backed by verification data.
- No API provider is allowed to leak transport DTOs into the domain layer.

## Initial scope

MeteoOne 1.0 targets:

- current conditions;
- hourly forecast up to 72 hours;
- three-day summary;
- current-location forecast with manual-location fallback;
- multiple weather models/providers;
- forecast fusion and qualitative model agreement;
- source/model comparison;
- offline cache;
- Russian and English localization;
- RuStore first, Google Play later.

## Distribution

- Alpha/beta: GitHub Releases.
- Stable: RuStore first, Google Play later.
- Android package: `com.sl.meteoone`.
- Published technical alpha: `0.1.0-alpha.1` (`versionCode = 1`).
- Published M3 pre-beta: `0.2.0-alpha.1` (`versionCode = 2`).
- Every selected release publishes a signed APK and signed AAB from the same reviewed source SHA.

## Status

M1 Forecast Core, M2 Offline-first Data Layer, M3 Product UI, and M4 Verification Engine are complete. `v0.2.0-alpha.1` remains the published feature-complete M3 GitHub prerelease with its signed APK+AAB pair; no M4 release has been selected. Production refresh now composes local verification evidence and may use guarded dynamic model-family weights only when the documented evidence, provenance, recency, materiality, and stability gates pass; otherwise fusion deterministically retains the equal-weight baseline.

See:

- [ROADMAP.md](ROADMAP.md) for milestone sequencing;
- [PRIVACY.md](PRIVACY.md) for the current alpha privacy policy;
- [docs/release/RUSTORE_ALPHA.md](docs/release/RUSTORE_ALPHA.md) for the retired alpha-store path and current stable-store policy;
- [docs/release/SIGNING.md](docs/release/SIGNING.md) for signing and integrity controls;
- [docs/release/M3_EXIT_REVIEW.md](docs/release/M3_EXIT_REVIEW.md) for the Product UI exit and pre-beta readiness review;
- [docs/release/M4_EXIT_REVIEW.md](docs/release/M4_EXIT_REVIEW.md) for the Verification Engine exit review and production evidence-safety gate;
- [docs/architecture/README.md](docs/architecture/README.md) for architecture;
- [docs/branding/BRAND_GUIDE.md](docs/branding/BRAND_GUIDE.md) for brand identity and voice;
- [docs/design/DESIGN_SYSTEM.md](docs/design/DESIGN_SYSTEM.md) for UI implementation rules;
- [docs/branding/APP_ICON.md](docs/branding/APP_ICON.md) for canonical app-icon handling.

## License

No open-source license is granted at this stage. All rights reserved.
