# MeteoOne security baseline

Status: active repository and CI baseline

MeteoOne follows the same practical security principles as the maintainer's hardened repositories, adapted to a public pre-release Android/Kotlin application with a standard-library Python research harness, a contained native/JNI GRIB decoder boundary, and no backend.

## Repository and change flow

The public repository has an active `Protect main` ruleset that enforces:

- changes through pull requests;
- blocked branch deletion and non-fast-forward updates;
- conversation resolution before merge;
- strict required checks with an up-to-date branch;
- squash-only linear history;
- no bypass actors;
- no fake mandatory approval requirement for a single-maintainer repository.

Secret scanning and push protection are enabled.

## Merge security gates

The currently verified live required `main` gates are:

- `verify`: repository-policy/research/JVM tests, Android lint, debug APK, release AAB, CI supply-chain policy, app identity, canonical-icon integrity, location privacy, and vendored native-bundle integrity;
- `gitleaks`: full-history secret scan;
- `Semgrep`: blocking SAST/security rules.

Dependency Review is operational on the public repository. It passed real PR #17 with exact context `dependency-review` and is eligible to become required once that owner-side ruleset update is applied and verified.

CodeQL is not a merge gate while its Kotlin extractor is incompatible with the application compiler. A clean uncached public-PR validation using CodeQL action `4.37.9` / CLI `2.27.0` rejected Kotlin `2.4.20` as too recent. MeteoOne does not downgrade Kotlin for scanner compatibility and does not accept Java-only/no-build analysis as Kotlin coverage. Automatic CodeQL jobs remain gated until a manual compatibility probe succeeds with the current application toolchain.

Qodana is scheduled/manual whole-repository defense in depth and is intentionally not required.

## CI/CD supply chain

- external Actions: full immutable SHA only;
- Docker actions and workflow containers: immutable image digest only;
- dynamic workflow container images are rejected because their provenance cannot be verified statically;
- top-level workflow permissions: deny by default;
- checkout credentials: never persisted;
- `pull_request_target`: forbidden;
- inherited reusable-workflow secrets: forbidden;
- write, OIDC, signing, and publication permissions are absent from ordinary PR workflows;
- Gradle wrapper distribution has a pinned SHA-256.

## Application attack surface

Current M1 application baseline:

- no accounts/backend in the repository;
- no analytics or advertising SDK;
- no precise or background location permission; foreground location uses only `ACCESS_COARSE_LOCATION`;
- Android source-set manifests and Kotlin/Java sources are scanned for location-boundary regressions;
- `android:allowBackup="false"`;
- `android:usesCleartextTraffic="false"`;
- only the launcher Activity is exported;
- exact location is treated as transient sensitive data;
- production network execution is TLS-only and bounded;
- `:forecast:data` contains the selected ecCodes 2.48.0 + libaec 1.1.4 native runtime and MeteoOne JNI bridge, with bounded payload/value limits, vendored definitions, licence/provenance records, and native-bundle verification.

M1 keeps native/full-grid representations inside `:forecast:data`; only MeteoOne-owned types cross the module boundary. M2 persistence/cache/retry/rate-limit policy is not implemented yet.

## Research exception

The Roshydromet WIS2 benchmark endpoint is currently HTTP-only in the measured M0 environment. It carries public observation data, not credentials, and is isolated to `research/`. Its payload cannot be described as transport-authenticated or integrity-protected. Research HTTP clients validate redirect destinations before following them and bound response reads.

## Release integrity

No GitHub Release exists yet, so MeteoOne deliberately does not maintain an unused privileged release workflow.

Before the first public/store release, the repository must implement and verify:

- protected release environment/signing material;
- release source tied to an exact verified `main` commit/tag;
- immutable semver `v*` tags where repository enforcement supports them;
- signed APK and AAB with expected certificate fingerprint verification;
- SHA-256 checksums;
- R8 mapping preservation when applicable;
- GitHub artifact attestation/provenance when technically available;
- cleanup of temporary signing material.

See `docs/release/SIGNING.md`.

## Deliberate non-controls

The repository does not add multiple overlapping SAST products as blocking gates, fake approvals, downgrade the application toolchain for scanner compatibility, or add unstable external vulnerability scanners simply to increase a score. Every required control must protect a real threat boundary and remain operationally reliable.
