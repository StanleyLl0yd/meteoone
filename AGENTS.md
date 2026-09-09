# MeteoOne — Agent Instructions

This file is the root operating contract for coding agents working in this repository.

Keep it high-signal. Do not duplicate detailed product, architecture, release, or security documentation here when a stable source already exists.

## Scope

These instructions apply to the entire repository unless a more specific nested `AGENTS.md` exists for a subdirectory.

## Instruction precedence

When instructions conflict, use this order:

1. The project owner's explicit current instruction.
2. This `AGENTS.md`.
3. Accepted Architecture Decision Records in `docs/adr/`.
4. `SECURITY.md` for security, privacy, secrets, and signing requirements.
5. `ROADMAP.md` for current milestone scope and sequencing.
6. `docs/architecture/README.md` for architectural direction.
7. `CONTRIBUTING.md` for repository workflow.
8. `README.md` for product-level summary.

If two authoritative documents conflict, do not silently choose a new product or architecture direction. Preserve the safer/current behavior and make the conflict explicit in the change.

## Project identity

MeteoOne is an Android weather application that combines forecasts from multiple weather models and providers into one local forecast.

Product tagline:

`Many models. One forecast.`

Android application ID:

`com.sl.meteoone`

Current repository phase:

`Pre-alpha / foundation`

Current product sequence is defined by `ROADMAP.md`.

Do not implement later roadmap milestones merely because the architecture could support them.

## Product invariants

Preserve these unless the owner explicitly changes them:

- Show one clear forecast first; model/source comparison is secondary and on demand.
- A weather model and a data provider are different concepts.
- Multiple providers exposing the same underlying model must not be treated as independent ensemble votes.
- Forecast fusion must remain robust and explainable before any ML-based approach is introduced.
- Numeric confidence must not be presented as calibrated probability until real verification data supports calibration.
- Exact device location is sensitive transient data and must not be persisted.
- The product is offline-first.
- A usable cached forecast should be available immediately when possible, with refresh performed separately.
- Partial provider failure must degrade gracefully.
- A failure of one provider must not make the app unusable when another valid forecast or usable cache exists.
- Provider transport DTOs must not leak into domain or presentation code.
- Russian and English localization are part of the initial product scope.
- RuStore is the first distribution target; Google Play follows later.

## Architecture invariants

The canonical flow is conceptually:

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
Local persistence
    ↓
Repository Flow
    ↓
Compose UI
```

### Model/provider separation

Treat model family and provider as separate domain concepts.

Adding a new provider does not automatically add a new independent forecast signal.

Fusion, verification, diagnostics, and source comparison must preserve enough metadata to reason about:

- model family;
- provider pipeline;
- forecast parameter;
- location/region;
- lead time.

See `docs/adr/0001-model-provider-separation.md`.

### Domain independence

Domain and fusion logic must remain independent of Android framework concerns and provider/network implementation details.

Do not make domain code depend on:

- Android framework types;
- Compose;
- Retrofit;
- Room;
- provider DTOs;
- provider SDKs.

The fusion engine should remain JVM-testable.

### Offline-first data flow

Persistent local state is the UI source of truth once the data layer exists.

Network refresh updates normalized persistence.

Presentation observes repository/local state instead of rendering directly from transient network DTOs.

Do not introduce a network-first UI path that bypasses cache/state consistency without an explicit architecture decision.

### Graceful degradation

Provider aggregation must support partial success.

Do not make all-provider success a prerequisite for producing a forecast unless a specific operation truly requires it.

Differentiate:

- complete success;
- partial success;
- stale-cache fallback;
- total failure.

### Explainability before ML

Version 1 fusion should use deterministic/robust statistical methods.

Do not introduce ML as a replacement for an explainable baseline before a verification dataset exists.

If ML is introduced later, preserve a deterministic fallback and measurable comparison against the baseline.

## Location privacy

Exact device coordinates may exist only transiently while resolving the forecast location.

Do not persist exact coordinates in:

- Room;
- DataStore;
- files;
- logs;
- analytics;
- crash reports;
- test fixtures;
- snapshots;
- issue attachments;
- debug dumps.

Persist only the normalized forecast location/grid cell needed for product behavior.

Background location tracking is outside the initial scope.

Any future requirement for persistent precise location requires a new architecture/security review.

See:

- `docs/adr/0002-location-privacy.md`;
- `SECURITY.md`.

## Android baseline

Preserve the current Android product baseline unless the owner explicitly approves a change:

- minimum Android: API 26;
- targetSdk: at least API 36 and current store-compliant at release time;
- compileSdk: not below targetSdk and compatible with the selected stable Android toolchain;
- arm64-v8a support is mandatory when native code is present;
- all native/JNI/NDK dependencies and final release artifacts must remain compatible with 16 KB memory pages;
- AAB is the primary store artifact;
- signed APK is the supplementary/direct-install artifact.

Do not raise minSdk without a concrete technical or product reason.

Before a release, verify current Google Play and RuStore requirements rather than assuming an old documented requirement is still sufficient.

## Planned Android architecture

The currently planned modular direction is:

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

This is a direction, not a mandate to create empty modules.

Adjust the split only when real dependency boundaries justify it.

Do not create speculative modules, interfaces, repositories, use cases, or service abstractions solely for hypothetical future features.

Prefer the smallest architecture that preserves current boundaries and testability.

## Data and provider boundaries

Keep provider-specific parsing and transport models inside the data/network layer.

Normalize external responses before they enter the forecast domain.

Do not make UI behavior depend on provider-specific field names or DTO shapes.

Provider adapters should isolate:

- authentication;
- HTTP details;
- provider-specific units;
- missing-value conventions;
- response schemas;
- rate-limit behavior;
- error mapping.

Canonical domain models should express MeteoOne concepts rather than mirrors of remote JSON.

## Forecast fusion rules

Fusion logic must be explicit, deterministic, and testable.

Do not:

- treat duplicate exposure of one model family as independent evidence;
- hide missing providers by fabricating values;
- present arbitrary precision;
- convert qualitative agreement into a fake calibrated percentage;
- silently change weighting logic without tests and documentation.

When changing fusion behavior:

1. define the reason;
2. preserve or extend tests;
3. document material algorithm changes;
4. compare behavior on representative fixtures/backtests when available.

## Persistence and cache behavior

When Room/DataStore enter scope:

- keep forecast persistence normalized around product/domain needs;
- version schemas deliberately;
- add migration coverage for persisted user data;
- preserve stale-cache fallback behavior;
- distinguish data freshness from data availability;
- avoid storing sensitive transient location data.

Do not delete or rewrite user data casually during schema changes.

## Networking

Networking must be resilient but bounded.

Prefer:

- explicit timeouts;
- bounded retries;
- provider-aware rate-limit handling;
- cancellation propagation;
- structured error mapping;
- partial-result handling.

Avoid:

- infinite retry loops;
- retry storms;
- blocking UI on all providers;
- leaking raw provider exceptions into presentation;
- embedding provider secrets in the application.

## Dependencies

Use dependencies deliberately.

Before adding a dependency:

- confirm the platform/library does not already provide a simpler solution;
- prefer mature, maintained libraries;
- avoid overlapping libraries with the same purpose;
- avoid libraries added only for speculative future use;
- keep versions centralized in the version catalog once Android bootstrap exists.

For dependency updates:

- review release notes/changelog when material;
- verify Android/toolchain compatibility;
- run the relevant build/tests;
- do not combine unrelated dependency churn with feature work unless necessary.

## Security and secrets

Never commit:

- API keys;
- Android keystores;
- private signing keys;
- signing passwords;
- service-account credentials;
- tokens;
- production environment files;
- secret provider credentials;
- exact user location data.

Release signing material must remain outside Git.

Normal pull-request CI must not receive production signing material.

Use least-privilege CI permissions.

Pin third-party GitHub Actions to immutable revisions where practical.

See `SECURITY.md` and `docs/release/SIGNING.md`.

## Release signing and artifacts

Preserve one stable application identity and signing lineage across RuStore and Google Play.

Production release output should include, when applicable:

- signed AAB;
- signed APK;
- SHA-256 checksums;
- R8 mapping file;
- release notes.

Do not expose the application signing key merely to simplify CI.

## Code quality

Prefer simple, explicit implementations over speculative abstraction.

Rules:

- keep responsibilities clear;
- minimize mutable state;
- minimize hidden control flow;
- avoid duplicate sources of truth;
- avoid wrapper chains with no useful semantics;
- prefer standard platform/library functionality when it is simpler and sufficient;
- do not optimize for minimum line count;
- do not introduce architecture solely because it is fashionable;
- preserve behavior unless a change explicitly intends to modify it.

Comments must be:

- minimal;
- useful;
- current;
- written in English.

Do not keep commented-out legacy code.

Remove stale TODO/FIXME comments when their meaning is no longer actionable.

## Kotlin/Android implementation style

When Android source exists:

- prefer immutable state where practical;
- keep side effects at clear boundaries;
- keep UI state explicit;
- keep business/domain logic outside Composables;
- keep network/database DTOs out of UI;
- avoid Android framework dependencies in domain logic;
- keep user-facing strings in resources/localization;
- do not hardcode locale-sensitive formatting;
- avoid blocking work on the main thread;
- preserve lifecycle/cancellation correctness.

Do not introduce a DI framework, event bus, service locator, or reactive abstraction only because one might be useful later.

## UI and accessibility

MeteoOne should support:

- light and dark themes;
- adaptive layouts;
- readable weather information;
- Russian and English localization;
- accessibility baseline.

Do not sacrifice semantic accessibility for visual convenience.

Do not communicate weather state exclusively through color where an accessible alternative is practical.

Prefer clear hierarchy and legibility over decorative density.

## Testing

Behavior changes require appropriate tests when practical.

Highest-priority test areas:

- forecast normalization;
- model/provider identity;
- fusion logic;
- provider partial failure;
- stale-cache fallback;
- location normalization/privacy boundaries;
- mapper correctness;
- persistence migrations once persistence exists;
- critical repository state flows;
- release-sensitive build configuration.

Fusion/domain tests should run without Android instrumentation where practical.

For bugs, prefer a regression test that fails before the fix and passes after it.

Do not add tests that merely mirror implementation details without protecting meaningful behavior.

## Verification

Run the strongest relevant verification available for the changed area.

Depending on repository state, this can include:

- formatting;
- lint;
- JVM/unit tests;
- Android unit tests;
- instrumentation tests;
- Gradle checks;
- debug build;
- release build;
- dependency/security checks;
- static analysis;
- relevant research/backtest tooling.

Never claim a check passed unless it was actually run and completed successfully.

If a check cannot be run in the current environment, say so explicitly.

## Repository workflow

Default workflow:

1. Start from a clearly scoped task.
2. Work on a short-lived branch from `main`.
3. Keep the diff focused.
4. Avoid unrelated churn.
5. Update tests for behavior changes.
6. Update documentation when an invariant, architecture decision, release rule, or user-visible contract changes.
7. Open a pull request.
8. Wait for required checks.
9. Prefer squash merge unless preserving a meaningful commit series is useful.

Preferred branch prefixes:

- `feature/`
- `fix/`
- `research/`
- `chore/`
- `release/`
- `docs/`

## Documentation and ADRs

Do not duplicate detailed documentation in this file.

Update or add an ADR when changing a durable architecture/security decision such as:

- model/provider separation;
- location privacy;
- offline-first source-of-truth strategy;
- fusion ownership/boundaries;
- persistent precise location;
- signing/security trust model;
- major module boundaries.

Use `ROADMAP.md` for milestone sequencing, not as an architecture decision log.

Keep README concise and product-facing.

## Repository-wide audit and refactoring tasks

A request for a full audit, cleanup, optimization, simplification, or deep refactor is an implementation task, not a request for recommendations only.

The objective is to reduce the repository to the minimum necessary complexity while preserving current functionality, externally observable behavior, documented contracts, security/privacy guarantees, and supported platform behavior.

### Audit before editing

Before broad refactoring, inspect the whole relevant repository surface:

- production source;
- tests;
- resources/assets;
- build scripts;
- CI/CD;
- dependencies;
- configuration;
- documentation;
- platform integration;
- generated-code integration points.

Identify first:

- actual architecture;
- authoritative state;
- user-visible functionality;
- persistence formats;
- public/internal contracts;
- framework- or convention-driven entry points.

Do not remove code merely because textual search shows no direct call.

Check for indirect use through:

- callbacks;
- lifecycle hooks;
- reflection;
- serialization;
- dependency injection;
- manifests;
- resources;
- routing;
- generated code;
- build scripts;
- CI/release tooling;
- tests;
- platform integrations.

If reasonable uncertainty remains, preserve the code.

### Removal and simplification priorities

Actively remove or simplify when safety is demonstrated:

- dead/unreachable code;
- unused files/resources;
- obsolete legacy paths;
- duplicated implementations;
- redundant checks;
- redundant conversions/copies;
- unnecessary wrappers/helpers;
- unnecessary abstraction layers;
- obsolete feature flags;
- unused dependencies;
- stale commented-out code;
- stale migration leftovers;
- speculative architecture with no current value.

Prefer:

- deletion over deprecation when compatibility is not required;
- consolidation over parallel implementations;
- fewer states and branches;
- fewer sources of truth;
- simpler control flow;
- fewer allocations/copies in meaningful hot paths;
- standard library/platform behavior over unnecessary custom code.

Do not perform code golf or broad rewrites without measurable value.

### Behavior preservation

Unless explicitly requested, do not intentionally change:

- product behavior;
- UI/UX;
- forecast semantics;
- fusion semantics;
- privacy guarantees;
- persistence/exchange formats;
- public contracts;
- supported platform behavior;
- accessibility;
- security posture.

For risky refactors without coverage, add a minimal regression test first when practical.

### Refactor verification

Work in small coherent groups.

After meaningful groups, run relevant checks.

Perform a second cleanup pass after the first refactor pass to catch newly exposed:

- dead code;
- duplication;
- redundant abstractions;
- stale imports/dependencies;
- unnecessary state.

Never report a build/test/security check as green unless it actually ran.

## App icon source artwork

When the project owner provides a new app icon as a PNG and identifies it as the app icon, treat that exact PNG as the canonical source artwork.

Keep that source as the original raster PNG.

Do not trace, vectorize, redraw, restyle, recreate, or convert it to:

- SVG;
- vector PDF;
- Android VectorDrawable;
- SF Symbol;
- any other vector representation;

unless the project owner explicitly requests it.

Do not overwrite, recompress, optimize in place, or otherwise rewrite the canonical PNG.

Platform-required derivatives may be generated only as raster derivatives of that PNG.

Allowed derivatives include required:

- PNG size variants;
- ICO;
- ICNS;
- other raster/container outputs required by a target platform.

The visible artwork must remain unchanged unless explicitly requested.

Do not:

- crop;
- add padding;
- change colors;
- remove details;
- redraw elements;
- restyle the artwork;
- alter composition.

If an older icon in another format is currently canonical, keep it until the project owner explicitly supplies a replacement PNG as the new app icon.

Once supplied, that PNG becomes the canonical source and the asset pipeline should derive required icons from it rather than converting it to a vector source.

## Definition of done

A change is not complete merely because code was written.

Before considering work complete:

- required behavior is implemented;
- relevant tests/checks are updated and run where possible;
- no unrelated churn remains;
- security/privacy impact was considered;
- documentation is updated when needed;
- dependency/release impact is explicit;
- no secrets or signing material were introduced;
- the final diff is reviewed for unnecessary complexity.

For repository-wide audit/refactor work, also complete the required second pass and summarize what was removed, simplified, preserved, and verified.
