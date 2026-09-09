# MeteoOne security baseline

Status: active repository-code baseline

MeteoOne follows the same practical security principles as the maintainer's hardened repositories, adapted to a pre-release Android/Kotlin application with a standard-library Python research harness and no backend/native component.

## Repository and change flow

Target repository policy:

- changes reach `main` through pull requests;
- force pushes and branch deletion are blocked;
- conversations are resolved before merge;
- required checks use exact contexts that are actually emitted;
- squash/linear history is preferred for normal changes;
- no fake approval requirement is added to a single-maintainer repository.

The current private repository plan/API does not expose repository rulesets through the connected automation. This is an administrative remaining gap, tracked in `docs/security/GITHUB_SETTINGS.md` and issue #12.

## Merge security gates

Controls that can run without GitHub Advanced Security:

- `verify`: research/JVM tests, Android lint, debug APK, release AAB, CI supply-chain policy, app identity and canonical-icon integrity;
- `gitleaks`: full-history secret scan;
- `Semgrep`: blocking SAST/security rules.

CodeQL Java/Kotlin and Dependency Review are configured but are not claimed as active gates while the private repository lacks the required GitHub security feature.

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
- immutable semver `v*` tags where the repository plan supports enforcement;
- signed APK and AAB with expected certificate fingerprint verification;
- SHA-256 checksums;
- R8 mapping preservation when applicable;
- GitHub artifact attestation/provenance when technically available;
- cleanup of temporary signing material.

See `docs/release/SIGNING.md`.

## Deliberate non-controls

The repository does not add multiple overlapping SAST products as blocking gates, fake approvals, or unstable external vulnerability scanners simply to increase a score. Every required control must protect a real threat boundary and remain operationally reliable.
