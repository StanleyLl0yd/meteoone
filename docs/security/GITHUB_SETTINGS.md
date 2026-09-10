# GitHub repository security settings

These settings require repository-owner administration and cannot be applied by the connected automation identity.

A 2026-09-09 audit of this private repository confirmed that the repository rulesets API responds with a plan requirement to upgrade GitHub Pro or make the repository public. Do not claim ruleset enforcement is active until GitHub actually exposes and applies it.

## Desired ruleset for `main`

When repository rulesets/branch protection are available, target the default branch with:

- require changes through a pull request;
- require conversation resolution;
- require strict status checks and an up-to-date branch once CI runtime is acceptable;
- require linear history;
- block force pushes/non-fast-forward updates;
- block branch deletion;
- no routine bypass actors;
- do not require a human approval solely for a security score while the project has one human maintainer.

Use squash merge for ordinary feature/fix work. Disable merge/rebase methods when the repository plan/settings make squash-only enforcement practical.

## Required checks

After the security-hardening workflow has produced these contexts successfully, require:

- `verify`;
- `gitleaks`;
- `Semgrep`.

Do not configure a required context before verifying its exact emitted name.

While GitHub Advanced Security / GitHub Code Security is unavailable for this private repository, do not require the skipped contexts:

- `Analyze Java/Kotlin`;
- `dependency-review`.

When GHAS becomes available:

1. enable the applicable security product;
2. set repository Actions variable `GHAS_ENABLED=true`;
3. verify CodeQL and Dependency Review on a real PR;
4. require the exact successful contexts;
5. add code-scanning enforcement for medium-or-higher security alerts if the plan exposes that rule.

## Security analysis settings

Enable every feature available to the current plan:

- Dependency graph;
- Dependabot alerts;
- Dependabot security updates;
- secret scanning / push protection when available;
- private vulnerability reporting when available for the repository visibility/plan.

## Release tags

There are currently no GitHub Releases and no release-tag lifecycle to protect.

Before the first release that uses `vX.Y.Z` tags, add a tag rule targeting `refs/tags/v*` that prevents deletion and non-fast-forward/tag movement. A release tag must become immutable after creation.

## Actions and secrets

Keep workflow permissions denied/read-only by default. Grant write permission only to the smallest trusted job that requires it.

Production signing secrets must never be available to ordinary pull-request workflows. Use a separately protected release environment or another explicitly trusted release mechanism.

## Current administrative gap

Issue #12 tracks owner-side repository settings. Repository-code controls are active independently, but branch/ruleset enforcement, signed-commit requirements, immutable release-tag enforcement, and plan-gated code-scanning enforcement remain unverified until GitHub exposes the corresponding administrative features.
