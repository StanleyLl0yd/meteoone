# CI and dependency security baseline

## Principles

- Third-party GitHub Actions are pinned to immutable commit SHAs.
- Workflow permissions are minimal and declared explicitly.
- Pull-request builds receive no production signing material or provider secrets.
- Dependency changes are reviewed separately from ordinary build verification.
- CodeQL analyzes Java/Kotlin code on pull requests, main, and a weekly schedule.
- Dependabot opens dependency update pull requests; updates are not auto-merged.

## Dependency policy

Dependency updates are reviewed for:

- release status: stable only unless an exception is documented;
- Android/API compatibility;
- security advisories;
- transitive dependency changes;
- license and distribution impact;
- release APK/AAB behavior.

Production updates must pass the same CI gates as application code.

## Signing boundary

Normal CI is intentionally incapable of producing a production-signed artifact. Signing is introduced only in a trusted release workflow/environment as documented in `docs/release/SIGNING.md`.
