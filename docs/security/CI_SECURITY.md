# CI and dependency security baseline

## Active controls

MeteoOne CI is intentionally least-privilege and contains no production signing material.

Active controls:

- all external GitHub Actions are pinned to immutable 40-character commit SHAs;
- checkout credentials are explicitly not persisted;
- Gradle wrapper distribution integrity is pinned with SHA-256;
- `scripts/verify_ci_supply_chain.py` blocks mutable Actions, unpinned workflow containers, `pull_request_target`, persisted checkout credentials, inherited reusable-workflow secrets, and missing top-level workflow permissions;
- `scripts/verify_app_icon.py` protects the canonical launcher PNG byte hash and validates required raster dimensions without rewriting assets;
- Android/JVM/research tests, lint, debug APK and release AAB builds run in `verify`;
- Gitleaks scans pull requests, main, and a weekly schedule;
- Semgrep security-audit/secrets rules run on pull requests, main, weekly schedule, and manual dispatch;
- Qodana JVM Community runs on schedule/manual dispatch as defense in depth;
- Dependabot covers Gradle and GitHub Actions;
- secret scanning and push protection are enabled for the public repository;
- the active `Protect main` ruleset requires the proven `verify`, `gitleaks`, and `Semgrep` contexts.

Qodana is deliberately not a merge gate because a secondary external/tooling failure should not deadlock normal development.

## CodeQL and Dependency Review

The repository is public, so the previous private-repository GitHub Advanced Security plan limitation no longer blocks CodeQL code scanning or Dependency Review.

Dependency Review remains configured for pull requests and becomes a required `main` gate only after its exact context succeeds on a real public pull request.

CodeQL uses advanced setup for `java-kotlin` with a compiled build. Kotlin is not considered covered by a Java-only/no-build database: CodeQL initialization therefore precedes a manual Android `:app:assembleDebug` build, followed by analysis.

The application Kotlin version must not be downgraded merely to satisfy scanner compatibility. If CodeQL cannot extract the current Kotlin compiler version, keep the scan non-required, record the upstream compatibility gap, and retest when CodeQL support advances.

## Dependency policy

Dependency updates are reviewed for:

- known security advisories;
- stable release status unless an exception is documented;
- Android/API and Java/Kotlin/Gradle compatibility;
- transitive dependency changes;
- license/distribution impact;
- release APK/AAB behavior.

Dependabot is an update mechanism, not a complete vulnerability gate. Dependency Review supplies the native pull-request dependency-diff gate once its real public-repository context has been verified stable.

## Network boundary

Production Android cleartext traffic is disabled in the manifest.

The M0 research-only Roshydromet WIS2 adapter uses the provider's published HTTP endpoint because measured HTTPS connectivity was unavailable during the benchmark. The data is public and the limitation is explicitly documented; this exception must never be reused in production Android networking.

## Signing boundary

Ordinary CI cannot produce production-signed artifacts. Signing is introduced only through an explicitly trusted release environment and must satisfy `docs/release/SIGNING.md`.
