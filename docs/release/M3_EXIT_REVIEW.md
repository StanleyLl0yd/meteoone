# M3 Product UI exit review

Status: release-readiness candidate. Final completion requires the post-merge exact-`main` checks recorded on issue #178.

## Scope reviewed

M3 is presentation/product work over the completed M1 forecast core and M2 offline-first data layer. This review does not implement M4 verification, M5 provider health/backend work, or store publication.

The completed product surface contains exactly three primary destinations:

- Forecast;
- Models;
- Settings.

## Parent invariant review

### Location and privacy

- location permission is requested only after explicit user action;
- only Android coarse/approximate location is requested;
- the device coordinate is normalized to the canonical 0.1-degree forecast grid before persistence/cache identity;
- raw device coordinates do not enter Room;
- Android backup remains disabled;
- CI enforces the location-permission/API boundary.

### Offline-first source of truth

- Room remains the forecast source of truth;
- persisted ForecastTarget state restores across process restart;
- valid cached forecast remains visible while refresh is in progress;
- refresh failure does not replace a valid cached snapshot;
- FRESH / STALE / EXPIRED states remain explicit;
- Room v1 -> v2 migration preserves legacy fused cache rows and adds model-comparison evidence without destructive migration.

### Forecast presentation

- current conditions are presented from the fused forecast;
- hourly forecast remains available through the stored horizon;
- three-day summary is derived only from available hourly forecast data;
- missing values are rendered as unavailable rather than fabricated.

### Models

- ECMWF IFS, DWD ICON, and NOAA GFS are model families;
- provider paths remain provenance/delivery paths and never become duplicate independent model votes;
- persisted source evidence survives process restart/offline use;
- model comparison uses exact matching forecast timestamps;
- precipitation comparison additionally requires matching accumulation intervals;
- missing source values remain explicit gaps;
- no calibrated numeric confidence is shown.

### Settings and attribution

- Settings is reachable from bottom navigation;
- installed version/build identity is read from the installed package;
- privacy and offline behavior are described in-product;
- Open-Meteo, NOAA/NCEP, ECMWF, and DWD attribution/source links are exposed;
- project/privacy links are available;
- provider identity remains distinct from model-family identity.

## UI hardening

- Material semantic colors support light and dark themes;
- critical state meaning is also expressed in text, not hue alone;
- primary content is bounded to a readable wide-window width;
- overflow-prone action and provenance rows are large-font-safe;
- onboarding remains vertically scrollable under large text;
- Forecast, Models, and Settings use scrollable content where their content can exceed the viewport;
- navigation/buttons use Material controls with platform touch-target semantics;
- meaningful product section headings expose accessibility heading semantics;
- English and Russian resource keys, string/plural resource types, and format placeholders are CI-verified;
- Russian count strings use Android plural rules.

## Required verification gates

Ordinary CI requires:

- repository policy tests;
- forecast benchmark/capability regression tests;
- app localization parity;
- core/model/network/location/database/preferences tests and lint;
- forecast domain/data/repository tests, lint, and debug assembly;
- app JVM unit tests, lint, debug assembly, and release bundle;
- committed Room schema verification;
- native AAR verification;
- JNI/R8 release-boundary verification.

Security gates remain:

- Security and Quality;
- Dependency Review on pull requests;
- Secret Scan;
- CodeQL according to the repository's current configured/skipped policy.

The exit issue is not complete until the final M3 merge has successful exact-`main` CI, Security and Quality, and Secret Scan evidence.

## Pre-beta release readiness

The previous published technical release is `v0.1.0-alpha.1` / `versionCode=1`. It predates the completed M3 product UI and must not be reused.

The M3 pre-beta candidate is:

- `versionName=0.2.0-alpha.1`;
- `versionCode=2`.

The canonical signed-release workflow:

1. runs only from current canonical `main`;
2. requires exact-SHA successful main verification;
3. restores and verifies the configured release signing identity;
4. builds signed APK and AAB together in the same release run;
5. verifies package/version/signature/native-library invariants for both outputs;
6. creates the GitHub Release targeting that exact source SHA;
7. publishes exactly `meteoone-<version>.apk` and `meteoone-<version>.aab`.

Alpha and beta releases are GitHub-only. Repository automation has no RuStore publication path.

For a future M7 stable store handoff, GitHub release creation and store acceptance are separate gates: the APK from the exact stable GitHub Release must pass manual device acceptance before the unchanged AAB from that same release may be uploaded manually to RuStore. A failed APK test invalidates that AAB as a store candidate and requires a new monotonic release pair.

## Deferred work

These are intentionally not M3 findings:

- M4: observation ingestion, forecast verification metrics, bias/skill analysis, evidence-backed dynamic weights;
- M5: central backend/provider gateway, provider health, central rate limiting/cache;
- M6: beta security/privacy/accessibility audit, performance/battery profiling, device/API compatibility matrix;
- M7: stable RuStore signing/store configuration and manual publication after APK acceptance;
- later: calibrated numeric confidence, saved locations, alerts/nowcast, widgets/radar, additional models.

No unresolved product-scope finding is known after #177; final closure depends on this exit-review PR and exact-main verification.
