# MeteoOne

**Many models. One forecast.**

MeteoOne is an Android weather application that combines forecasts from multiple weather models and providers into one local forecast for the user's current location.

## Product principles

- One clear forecast first; model/source comparison on demand.
- Model and provider are separate concepts.
- Forecast fusion is robust and explainable before any ML is introduced.
- Exact user location is not persisted.
- Android-only: the app retrieves forecasts and observations directly and performs all decoding, verification and fusion on the phone.
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

- All new version names and tags use numeric `major.minor.patch` only, without suffixes.
- Android package: `com.sl.meteoone`.
- Testing-phase releases (0.x.y) are GitHub-only and flagged as GitHub prereleases via channel metadata, not version suffixes.
- Current signed Android-only maintenance test: `0.4.0` (`versionCode = 5`) after its release workflow succeeds.
- Historical tagged releases remain immutable to preserve binary provenance and the Android upgrade path.
- Stable store distribution begins only at the explicitly selected M7 release (RuStore first, Google Play later).
- Every selected release publishes a signed APK and signed AAB from the same reviewed source SHA.

## Status

M1 Forecast Core, M2 Offline-first Data Layer, M3 Product UI, and M4 Verification Engine are complete. The M5 server backend was cancelled by explicit product decision. MeteoOne is self-contained on Android: there is no proprietary MeteoOne server, backend URL, or dependency on MeteoOne cloud services. The most recent published test build before numeric-only naming was signed from canonical commit `0d7c17ae36f6c3e379df3a65347f8374d6dbfeaa`; `0.4.0` becomes current only after its own signed GitHub release is published. On 2026-10-09, the product owner reported passing device checklist scenarios 1-11; extended 1-2 day observation remains pending. These UI tests are not independent on-device proof of internal M4/GHCNh or native GRIB execution. Production refresh now composes local verification evidence and may use guarded dynamic model-family weights only when the documented evidence, provenance, recency, materiality, and stability gates pass; otherwise fusion deterministically retains the equal-weight baseline.

See:

- [ROADMAP.md](ROADMAP.md) for milestone sequencing;
- [PRIVACY.md](PRIVACY.md) for the current alpha privacy policy;
- [docs/release/RUSTORE_ALPHA.md](docs/release/RUSTORE_ALPHA.md) for the retired alpha-store path and current stable-store policy;
- [docs/release/SIGNING.md](docs/release/SIGNING.md) for signing and integrity controls;
- [docs/release/M3_EXIT_REVIEW.md](docs/release/M3_EXIT_REVIEW.md) for the Product UI exit and pre-beta readiness review;
- [docs/release/M4_EXIT_REVIEW.md](docs/release/M4_EXIT_REVIEW.md) for the Verification Engine exit review and production evidence-safety gate;
- [docs/architecture/README.md](docs/architecture/README.md) for architecture;
- [docs/architecture/ANDROID_ONLY_DECISION.md](docs/architecture/ANDROID_ONLY_DECISION.md) for the Android-only architecture decision;
- [docs/branding/BRAND_GUIDE.md](docs/branding/BRAND_GUIDE.md) for brand identity and voice;
- [docs/design/DESIGN_SYSTEM.md](docs/design/DESIGN_SYSTEM.md) for UI implementation rules;
- [docs/branding/APP_ICON.md](docs/branding/APP_ICON.md) for canonical app-icon handling.

## License

No open-source license is granted at this stage. All rights reserved.
