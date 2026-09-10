# GitHub repository security settings

These settings require repository-owner administration and must be verified against the live repository state before they are documented as enforced.

As of 2026-09-10, MeteoOne is public and repository ruleset enforcement is active.

## Active ruleset for `main`

The active `Protect main` repository ruleset targets the default branch and enforces:

- changes through a pull request;
- conversation resolution;
- strict required status checks with an up-to-date branch;
- linear history;
- no non-fast-forward updates;
- no branch deletion;
- squash as the allowed merge method;
- no bypass actors;
- zero mandatory human approvals, which avoids creating a fake approval gate for a single-maintainer repository.

The currently required successful check contexts are:

- `verify`;
- `gitleaks`;
- `Semgrep`.

Do not configure a required context before verifying its exact emitted name and a successful real pull-request run.

## CodeQL and Dependency Review

The public repository is eligible for GitHub CodeQL code scanning and Dependency Review without the previous private-repository plan limitation.

CodeQL must analyze the Kotlin application through a real compiled build. The advanced workflow uses `java-kotlin` with manual build mode and runs the Android debug build after CodeQL initialization. Do not downgrade the application Kotlin toolchain merely to make the scanner pass, and do not treat a Java-only/no-build scan as Kotlin coverage.

Before adding CodeQL or Dependency Review to `Protect main`:

1. run both on a real public pull request;
2. verify that CodeQL successfully extracts and analyzes the current Kotlin toolchain;
3. record the exact successful emitted check contexts;
4. add only those proven-stable contexts to the required checks.

If current CodeQL tooling does not support the repository's Kotlin compiler version, keep CodeQL non-required and document the upstream compatibility gap rather than weakening or downgrading the application toolchain.

## Security analysis settings

Current owner-side security configuration includes:

- Dependency graph / dependency security features available to the repository;
- Dependabot alerts and security updates;
- secret scanning;
- secret scanning push protection.

Keep every available security feature enabled unless a documented operational reason requires otherwise.

## Release tags

There are currently no GitHub Releases and no release-tag lifecycle to protect.

Before the first release that uses `vX.Y.Z` tags, add a tag rule targeting `refs/tags/v*` that prevents deletion and non-fast-forward/tag movement. A release tag must become immutable after creation.

## Actions and secrets

Keep workflow permissions denied/read-only by default. Grant write permission only to the smallest trusted job that requires it.

Production signing secrets must never be available to ordinary pull-request workflows. Use a separately protected release environment or another explicitly trusted release mechanism.

## Remaining administrative work

Issue #12 tracks the remaining owner-side security work. The `main` ruleset, secret scanning, and push protection are now verified active. Remaining work is limited to validating CodeQL/Dependency Review before making either a required gate and to the release signing/tag/attestation controls that become applicable before the first production release.
