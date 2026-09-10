# MeteoOne security baseline

Status: active repository and CI baseline

MeteoOne follows the same practical security principles as the maintainer's hardened repositories, adapted to a pre-release Android/Kotlin application with a standard-library Python research harness and no backend/native component.

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

- `verify`: research/JVM tests, Android lint, debug APK, release AAB, CI supply-chain policy, app identity and canonical-icon integrity;
- `gitleaks`: full-history secret scan;
- `Semgrep`: blocking SAST/security rules.

Dependency Review is operational on the public repository. It passed real PR #17 with exact context `dependency-review` and is eligible to become required once that owner-side ruleset update is applied and verified.

CodeQL is not a merge gate while its Kotlin extractor is incompatible with the application compiler. A clean uncached public-PR validation using CodeQL action `4.37.9` / CLI `2.27.0` rejected Kotlin `2.4.20` as too recent. MeteoOne does not downgrade Kotlin for scanner compatibility and does not accept Java-only/no-build analysis as Kotlin coverage. Automatic CodeQL jobs remain gated until a manual compatibility probe succeeds with the current application toolchain.

Qodana is scheduled/manual defense in depth and is intentionally not required.

## CI/CD supply chain

- external Actions: full immutable SHA only;
- workflow containers: immutable image digest;
- top-level workflow permissions: deny by default;
- checkout credentials: never persisted;
- `pull_request_target`: forbidden;
- inherited reusable-workflow secrets: forbidden;
- write, OIDC, signing, and publication permissions are absent from ordinary PR workflows;
- Gradle wrapper distribution has a pinned SHA-256.

## Application attack surface

Current application baseline:

- no accounts/backend in the repository;
- no analytics or advertising SDK;
- no dangerous runtime permissions;
- no native/JNI/NDK code;
- `android:allowBackup="false"`;
- `android:usesCleartextTraffic="false"`;
- only the launcher Activity is exported;
- exact location is treated as transient sensitive data.

Future M1 networking must preserve TLS-only production transport and provider/model provenance.

## Research exception

The Roshydromet WIS2 benchmark endpoint is currently HTTP-only in the measured M0 environment. It carries public observation data, not credentials, and is isolated to `research/`. Its payload cannot be described as transport-authenticated or integrity-protected.

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
