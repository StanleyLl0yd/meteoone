# Contributing

## Workflow

1. Start from an issue or a clearly scoped maintenance task.
2. Create a short-lived branch from `main`.
3. Keep changes focused and avoid unrelated churn.
4. Add or update tests for behavior changes.
5. Open a pull request and wait for required checks.
6. Prefer squash merge unless preserving a meaningful commit series is useful.

## Branch naming

- `feature/<topic>`
- `fix/<topic>`
- `research/<topic>`
- `chore/<topic>`
- `release/<version>`

## Code quality

- Prefer simple, explicit implementations over speculative abstractions.
- Keep transport DTOs inside the data layer.
- Do not introduce provider-specific types into domain or presentation code.
- Preserve offline-first behavior and graceful degradation.
- Comments should be minimal, useful, and written in English.
- Do not commit secrets, signing material, API keys, or exact user location data.

## Pull requests

Every PR should state:

- what changed;
- why it changed;
- how it was verified;
- security/privacy impact;
- whether dependencies or release behavior changed.
