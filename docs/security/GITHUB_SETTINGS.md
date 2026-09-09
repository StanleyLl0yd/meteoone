# GitHub repository security settings

The connected automation identity does not have repository-administration permission, so these settings must be applied by the repository owner in GitHub.

## Ruleset for `main`

Create a branch ruleset targeting the default branch with:

- require changes through a pull request;
- require one approval only when a second human maintainer is available; do not add fake self-approval requirements;
- require conversation resolution before merge;
- require status checks to pass;
- require the branch to be up to date before merge once CI runtime is acceptable;
- block force pushes;
- block branch deletion;
- do not allow bypass except for repository recovery/emergency administration.

Required checks for the current private-repository baseline:

- `verify` from the CI workflow;
- `gitleaks` from Secret Scan.

Do not require the currently skipped CodeQL or Dependency Review jobs until GitHub Advanced Security / GitHub Code Security is enabled.

## Security analysis

Enable all features available to the current repository plan:

- Dependency graph;
- Dependabot alerts;
- Dependabot security updates;
- private vulnerability reporting when/if the repository becomes public.

For this private repository, GitHub Dependency Review and CodeQL require GitHub Advanced Security / GitHub Code Security. If enabled later:

1. enable the GitHub security product;
2. create repository Actions variable `GHAS_ENABLED=true`;
3. verify CodeQL and Dependency Review both pass;
4. add their checks to the `main` ruleset.

## Actions

Keep workflow permissions read-only by default. Grant write permission only at job scope where a specific trusted workflow requires it.

Production signing secrets must not be added to ordinary repository Actions secrets used by pull-request workflows. Release signing belongs in a separately protected release environment or trusted self-hosted runner.

## Merge policy

Use squash merge for normal feature/fix pull requests so `main` remains concise. Keep merge commits only when preserving a meaningful multi-commit history is necessary.

Delete merged short-lived branches after merge.
