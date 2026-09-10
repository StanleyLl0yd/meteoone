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
- Dependabot covers Gradle and GitHub Actions.

Qodana is deliberately not a merge gate because a secondary external/tooling failure should not deadlock normal development.

## GitHub Advanced Security boundary

This repository is private. GitHub CodeQL code scanning and Dependency Review require GitHub Advanced Security / GitHub Code Security for the current repository plan.

Their workflows remain ready and are gated by:

`vars.GHAS_ENABLED == 'true'`

or by the repository becoming public.

When the feature becomes available:

1. enable the applicable GitHub security product;
2. set repository Actions variable `GHAS_ENABLED=true`;
3. verify CodeQL and Dependency Review both pass;
4. make their real check contexts required for `main`.

CodeQL uses `build-mode: none` deliberately because the current compiler-tracing path does not support the selected Kotlin 2.4.20 toolchain reliably. Source extraction avoids downgrading the application toolchain merely for a scanner.

## Dependency policy

Dependency updates are reviewed for:

- known security advisories;
- stable release status unless an exception is documented;
- Android/API and Java/Kotlin/Gradle compatibility;
- transitive dependency changes;
- license/distribution impact;
- release APK/AAB behavior.

Dependabot is an update mechanism, not a complete vulnerability gate. Until Dependency Review is available on this private repository, dependency vulnerability enforcement remains a documented plan-level gap rather than being replaced with a flaky external SaaS scanner.

## Network boundary

Production Android cleartext traffic is disabled in the manifest.

The M0 research-only Roshydromet WIS2 adapter uses the provider's published HTTP endpoint because measured HTTPS connectivity was unavailable during the benchmark. The data is public and the limitation is explicitly documented; this exception must never be reused in production Android networking.

## Signing boundary

Ordinary CI cannot produce production-signed artifacts. Signing is introduced only through an explicitly trusted release environment and must satisfy `docs/release/SIGNING.md`.
