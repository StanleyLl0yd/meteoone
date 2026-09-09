# CI and dependency security baseline

## Principles

- Third-party GitHub Actions are pinned to immutable commit SHAs.
- Workflow permissions are minimal and declared explicitly.
- Pull-request builds receive no production signing material or provider secrets.
- Dependabot opens dependency update pull requests; updates are not auto-merged.
- Gitleaks scans pull requests and main for committed secrets.
- GitHub Advanced Security workflows are kept ready but are not treated as active controls while this private repository lacks GHAS.

## GitHub Advanced Security boundary

GitHub Dependency Review and CodeQL code scanning require GitHub Advanced Security / GitHub Code Security for this private repository.

The workflows are therefore gated by:

`vars.GHAS_ENABLED == 'true'`

or by the repository becoming public.

When GHAS is enabled, set the repository variable `GHAS_ENABLED=true`. Dependency Review and CodeQL then activate without changing workflow code.

CodeQL uses `build-mode: none` deliberately. The current CodeQL 2.26.4 compiler tracer rejects Kotlin 2.4.20 as newer than its traced-build support. Source extraction avoids forcing MeteoOne to downgrade its stable Kotlin toolchain merely for a scanner.

## Dependency policy

Dependency updates are reviewed for:

- stable release status unless an exception is documented;
- Android/API compatibility;
- security advisories;
- transitive dependency changes;
- license and distribution impact;
- release APK/AAB behavior.

Production updates must pass the same CI gates as application code.

## Signing boundary

Normal CI is intentionally incapable of producing a production-signed artifact. Signing is introduced only in a trusted release workflow/environment as documented in `docs/release/SIGNING.md`.
