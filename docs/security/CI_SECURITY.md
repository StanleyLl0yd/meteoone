# CI and dependency security baseline

## Active controls

MeteoOne CI is intentionally least-privilege and contains no production signing material.

Active controls:

- all external GitHub Actions are pinned to immutable 40-character commit SHAs;
- Docker actions and workflow container images are pinned to immutable SHA-256 digests; dynamic workflow container expressions are rejected because the repository verifier cannot establish their image provenance statically;
- checkout credentials are explicitly not persisted;
- Gradle wrapper distribution integrity is pinned with SHA-256;
- `scripts/verify_ci_supply_chain.py` blocks mutable Actions, mutable Docker actions, unpinned or dynamically selected workflow containers, `pull_request_target`, persisted checkout credentials, inherited reusable-workflow secrets, and missing top-level workflow permissions;
- `scripts/verify_app_icon.py` protects the canonical launcher PNG byte hash and validates required raster dimensions without rewriting assets;
- `scripts/verify_location_privacy.py` scans every Android source-set manifest plus Kotlin and Java sources, rejects precise/background location permissions, duplicate coarse-location ownership, and Android location API usage outside `:core:location`;
- repository-policy verifier unit tests, Android/JVM/research tests, lint, debug APK and release AAB builds run in `verify`;
- Gitleaks scans pull requests, main, and a weekly schedule;
- Semgrep security-audit/secrets rules run on pull requests, main, weekly schedule, and manual dispatch;
- Qodana JVM Community runs over the whole repository on schedule/manual dispatch as defense in depth;
- Dependabot covers Gradle and GitHub Actions;
- secret scanning and push protection are enabled for the public repository;
- the active `Protect main` ruleset requires the proven `verify`, `gitleaks`, and `Semgrep` contexts.

Qodana is deliberately not a merge gate because a secondary external/tooling failure should not deadlock normal development.

## Dependency Review

The repository is public, so the previous private-repository GitHub Advanced Security plan limitation no longer blocks Dependency Review.

Dependency Review was verified successfully on real public PR #17, including run `34456327413`. Its exact job/check context is `dependency-review`. It is therefore eligible to become a required `main` gate; the live ruleset must not be documented as requiring it until that owner-side ruleset update is actually applied and verified.

## CodeQL Kotlin analysis

MeteoOne keeps CodeQL advanced setup configured for `java-kotlin` with a real compiled Android build; a Java-only/no-build database is not accepted as Kotlin coverage.

The earlier CodeQL CLI used by public PR #17 rejected Kotlin `2.4.20` as too recent. The post-M3 repository audit re-tested the current CodeQL action/toolchain combination and restored automatic pull-request, push, scheduled, and manual Java/Kotlin analysis without downgrading the application Kotlin version. The workflow is no longer gated by `CODEQL_KOTLIN_SUPPORTED`.

CodeQL is still **not** listed in the live `Protect main` required status contexts. It should become a required context only after the restored workflow has proved stable on real audit PR/main runs and the owner explicitly updates and re-reads the live ruleset.

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

The M0 research-only Roshydromet WIS2 adapter uses the provider's published HTTP endpoint because measured HTTPS connectivity was unavailable during the benchmark. The data is public and the limitation is explicitly documented; this exception must never be reused in production Android networking. Research HTTP redirect targets are validated before they are followed, and response reads are bounded.

## Signing boundary

Ordinary CI cannot produce production-signed artifacts. Signing is introduced only through the explicitly trusted manual signed-release workflow using repository secrets; no GitHub Environment is required. The workflow must satisfy `docs/release/SIGNING.md`.
