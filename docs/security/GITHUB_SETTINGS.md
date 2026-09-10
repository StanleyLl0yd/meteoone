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

The currently verified live required successful check contexts are:

- `verify`;
- `gitleaks`;
- `Semgrep`.

Do not document a context as required before verifying both its exact emitted name and the live ruleset state.

## Dependency Review

Dependency Review was verified successfully twice on real public PR #17. The latest validation is run `34456327413`, and the exact successful job/check context is `dependency-review`.

This context is eligible to be added to `Protect main`. The connected GitHub automation does not expose repository-ruleset mutation, so this remains an owner-side ruleset action until the live ruleset is updated and re-read. Do not claim `dependency-review` is required before that verification.

## CodeQL

CodeQL must analyze the Kotlin application through a real compiled build. A Java-only `build-mode: none` database is not accepted as Kotlin coverage.

Public PR #17 tested a clean uncached compiled CodeQL path with action `4.37.9` / CLI `2.27.0`. Run `34456327378` reached real Kotlin compilation and failed because the CodeQL Kotlin interceptor rejected Kotlin `2.4.20` as too recent, explicitly reporting support only for versions below `2.4.20`.

MeteoOne does not downgrade the application Kotlin toolchain merely to make a scanner pass. Automatic CodeQL execution is therefore gated by repository variable `CODEQL_KOTLIN_SUPPORTED=true`. `workflow_dispatch` remains the explicit compatibility probe. When a manual probe succeeds against the then-current application toolchain:

1. set `CODEQL_KOTLIN_SUPPORTED=true`;
2. verify CodeQL on a real pull request;
3. record the exact successful check context;
4. add that context to `Protect main` only after it is proven stable.

Until then, CodeQL is an explicitly documented upstream compatibility gap, not a merge gate.

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

Issue #12 tracks owner-side repository security settings. The `main` ruleset, secret scanning, and push protection are verified active. The immediate remaining owner action is adding the already-proven `dependency-review` context to `Protect main` and verifying the live ruleset. CodeQL compatibility is tracked separately from owner administration because the blocker is the upstream Kotlin extractor. Release signing/tag/attestation controls become applicable before the first production release.
