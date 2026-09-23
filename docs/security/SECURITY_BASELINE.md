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

CodeQL runs a real compiled `java-kotlin` Android build. Temporary probe #232 / run `35892590708` / job `107288489070` explicitly selected stable CLI 2.27.1 and successfully completed traced `:app:assembleDebug` plus `security-extended` analysis against Kotlin 2.4.20. Production PR #233 then enabled that stable bundle for ordinary CodeQL execution; its first pull-request run `35893591476` / job `107291858207` also succeeded. MeteoOne does not downgrade Kotlin, use nightly as production coverage, or accept Java-only/no-build analysis as Kotlin coverage. CodeQL remains outside the live required-check ruleset until canonical-main verification and an owner-side ruleset update are both completed.

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

Current post-M4 application baseline:

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

Native/full-grid representations remain inside `:forecast:data`; only MeteoOne-owned types cross module boundaries. M2 persistence/cache/freshness/retry/rate-limit behavior and the M3 Forecast/Models/Settings product surfaces are implemented, with Room as the offline source of truth.

## Research exception

The Roshydromet WIS2 benchmark endpoint is currently HTTP-only in the measured M0 environment. It carries public observation data, not credentials, and is isolated to `research/`. Its payload cannot be described as transport-authenticated or integrity-protected. Research HTTP clients validate redirect destinations before following them and bound response reads.

## Release integrity

GitHub prereleases `v0.1.0-alpha.1` and `v0.2.0-alpha.1` exist. The canonical release workflow:

- runs only from canonical verified `main`;
- enforces monotonic Android `versionCode`/unique release history;
- restores signing material only in the dedicated trusted release job;
- verifies the expected certificate before and after signing;
- builds APK and AAB together from one exact source SHA;
- verifies package/version/signature/native-library invariants;
- publishes exactly the signed APK and AAB as GitHub Release assets;
- removes temporary signing material.

Alpha/beta remain GitHub-only. A future stable RuStore AAB is eligible for manual upload only after the APK from the same GitHub Release passes manual device acceptance.

Release-tag immutability is **not yet enforced by a tag ruleset** and remains an owner/admin finding tracked with repository settings. See `docs/release/SIGNING.md` and `docs/security/GITHUB_SETTINGS.md`.

## Deliberate non-controls

The repository does not add multiple overlapping SAST products as blocking gates, fake approvals, downgrade the application toolchain for scanner compatibility, or add unstable external vulnerability scanners simply to increase a score. Every required control must protect a real threat boundary and remain operationally reliable.
