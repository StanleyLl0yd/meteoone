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

CodeQL analyzes the Kotlin application through a real compiled `java-kotlin` build. A Java-only `build-mode: none` database is not accepted as Kotlin coverage.

Temporary probe #232 / run `35892590708` / job `107288489070` explicitly selected stable CodeQL 2.27.1 and proved compatibility with Kotlin 2.4.20: initialization, the traced `:app:assembleDebug` build, and `security-extended` analysis all succeeded. The probe was closed unmerged.

Production PR #233 removes the compatibility gate and explicitly selects the stable 2.27.1 bundle while pinned CodeQL action 4.38.1 still defaults to 2.27.0. Its first ordinary pull-request run `35893591476` / job `107291858207` succeeded. The exact emitted job/check context is `Analyze Java/Kotlin`.

CodeQL now runs on pull requests, pushes to `main`, the weekly schedule, and manual dispatch. Canonical main `1027c614621738d2351c6e07572d641f8c9c832e` passed CodeQL run `35895324867` / job `107297696780`, so the workflow is proven on both PR and `main`. It is not yet a live required context in `Protect main`; adding `Analyze Java/Kotlin` is now an owner-side ruleset action, and the live ruleset must be re-read before documenting it as enforced.

## Security analysis settings

Current owner-side security configuration includes:

- Dependency graph / dependency security features available to the repository;
- Dependabot alerts and security updates;
- secret scanning;
- secret scanning push protection.

Keep every available security feature enabled unless a documented operational reason requires otherwise.

## Release tags

GitHub prereleases `v0.1.0-alpha.1` and `v0.2.0-alpha.1` now exist. The live repository currently has no tag-target ruleset, so release-tag immutability is an outstanding owner-side control.

Add a tag ruleset targeting `refs/tags/v*` that prevents deletion and tag movement. Re-read the live rulesets after applying it and only then document release tags as enforced immutable.

## Actions and secrets

Keep workflow permissions denied/read-only by default. Grant write permission only to the smallest trusted job that requires it.

Production signing secrets must never be available to ordinary pull-request workflows. Use a separately protected release environment or another explicitly trusted release mechanism.

## Remaining administrative work

Issue #12 tracks owner-side repository security settings. The `main` ruleset, secret scanning, and push protection are verified active. Remaining owner actions are: add the already-proven `dependency-review` context to `Protect main`, add and verify a `refs/tags/v*` immutability ruleset now that GitHub releases exist, and—after canonical-main CodeQL verification—add the proven `Analyze Java/Kotlin` context if CodeQL is to be merge-required.
