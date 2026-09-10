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

## Dependency Review

The repository is public, so the previous private-repository GitHub Advanced Security plan limitation no longer blocks Dependency Review.

Dependency Review was verified successfully on real public PR #17, including run `34456327413`. Its exact job/check context is `dependency-review`. It is therefore eligible to become a required `main` gate; the live ruleset must not be documented as requiring it until that owner-side ruleset update is actually applied and verified.

## CodeQL compatibility boundary

Kotlin is not considered covered by a Java-only/no-build database. MeteoOne therefore keeps CodeQL advanced setup configured for `java-kotlin` with a real compiled Android build rather than using `build-mode: none`.

A clean uncached validation on public PR #17 proved that the current scanner cannot analyze the application toolchain. CodeQL action `4.37.9` / CLI `2.27.0` rejected Kotlin `2.4.20` during `:core:model:compileKotlin` with `KotlinVersionTooRecentError` and the explicit message that CodeQL supports versions below `2.4.20` (run `34456327378`).

The application Kotlin version must not be downgraded merely to satisfy scanner compatibility. Automatic CodeQL jobs are therefore gated by repository variable `CODEQL_KOTLIN_SUPPORTED=true`; while it is unset/false, pull-request, push, and scheduled CodeQL jobs skip. `workflow_dispatch` remains available as an explicit compatibility probe. After a manual probe succeeds with the current application toolchain, set the variable, verify a real PR, and only then consider the exact CodeQL context for `main` protection.

## Dependency policy

Dependency updates are reviewed for:

- known security advisories;
- stable release status unless an exception is documented;
- Android/API and Java/Kotlin/Gradle compatibility;
- transitive dependency changes;
- license/distribution impact;
- release APK/AAB behavior.

Dependabot is an update mechanism, not a complete vulnerability gate. Dependency Review supplies the native pull-request dependency-diff gate and has been verified operational on the public repository.

## Network boundary

Production Android cleartext traffic is disabled in the manifest.

The M0 research-only Roshydromet WIS2 adapter uses the provider's published HTTP endpoint because measured HTTPS connectivity was unavailable during the benchmark. The data is public and the limitation is explicitly documented; this exception must never be reused in production Android networking.

## Signing boundary

Ordinary CI cannot produce production-signed artifacts. Signing is introduced only through an explicitly trusted release environment and must satisfy `docs/release/SIGNING.md`.
