# MeteoOne

**Many models. One forecast.**

MeteoOne is an Android weather application that combines forecasts from multiple weather models and providers into one local forecast for the user's current location.

## Product principles

- One clear forecast first; model/source comparison on demand.
- Model and provider are separate concepts.
- Forecast fusion is robust and explainable before any ML is introduced.
- Exact user location is not persisted.
- Offline-first: cached forecast is shown immediately and refreshed explicitly in the current alpha slice.
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

- Primary: RuStore
- Later: Google Play
- Android package: `com.sl.meteoone`
- First closed-alpha version: `0.1.0-alpha.1` (`versionCode = 1`)
- Release artifacts: signed AAB and APK

## Status

M2 offline-first data layer is complete. The project is preparing `0.1.0-alpha.1` for private RuStore alpha testing; M3 product-UI work has not started.

See:

- [ROADMAP.md](ROADMAP.md) for milestone sequencing;
- [PRIVACY.md](PRIVACY.md) for the current alpha privacy policy;
- [docs/release/RUSTORE_ALPHA.md](docs/release/RUSTORE_ALPHA.md) for the first RuStore alpha gate;
- [docs/release/SIGNING.md](docs/release/SIGNING.md) for signing and integrity controls;
- [docs/architecture/README.md](docs/architecture/README.md) for architecture;
- [docs/branding/BRAND_GUIDE.md](docs/branding/BRAND_GUIDE.md) for brand identity and voice;
- [docs/design/DESIGN_SYSTEM.md](docs/design/DESIGN_SYSTEM.md) for UI implementation rules;
- [docs/branding/APP_ICON.md](docs/branding/APP_ICON.md) for canonical app-icon handling.

## License

No open-source license is granted at this stage. All rights reserved.
