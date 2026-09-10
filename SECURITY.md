# Security Policy

MeteoOne is currently private and pre-release. There is no supported public release yet.

## Reporting a vulnerability

Do not disclose a suspected vulnerability, credential, signing material, token, private key, or exact user-location data in a public issue, pull request, commit message, or CI log.

Report security issues to the repository owner through an established private channel. If GitHub Private Vulnerability Reporting becomes available for this repository, prefer that mechanism.

Include the affected commit or build, concise reproduction steps, expected impact, and sanitized evidence. Stop testing once the minimum evidence needed to establish a credential/signing compromise has been collected.

## Triage targets

- acknowledgement: within 3 business days;
- initial severity/scope assessment: within 7 business days;
- critical and high-impact issues take priority over feature work.

These are response targets, not guaranteed remediation dates.

## Security scope

In scope:

- Android application code, manifest, packaged resources, and network policy;
- forecast-provider adapters and normalization boundaries;
- exact-location privacy and unintended persistence/logging;
- dependency and build-toolchain risks introduced by this repository;
- GitHub Actions, CI/CD, repository supply chain, signing, provenance, and release integrity;
- research tooling when a repository-owned implementation creates an additional security risk.

There is currently no backend/server or native/JNI/NDK component in the repository.

## Secrets and signing

Never commit:

- Android keystores or private signing keys;
- signing passwords;
- provider API secrets;
- service-account credentials;
- production environment files;
- authentication tokens or private certificates.

Normal pull-request workflows must not receive production signing material. Release signing material must remain outside Git and be supplied only to an explicitly trusted release environment.

## Location privacy

Exact device coordinates are transient sensitive data. They must not be persisted, logged, included in analytics, crash reports, issue fixtures, snapshots, or debug dumps. Persist only the normalized forecast location/grid cell required for product behavior.

## Network security

Production Android traffic must use authenticated TLS; the application manifest forbids cleartext traffic.

The M0 research harness contains one documented exception: the public Roshydromet WIS2 SYNOP endpoint currently used for benchmark truth is HTTP-only. That transport is unauthenticated and has no transport integrity. It is research-only and must never become a production Android transport path.

## CI and dependency security

- external GitHub Actions use immutable full commit SHAs;
- workflow token permissions follow least privilege;
- CI policy checks reject unsafe workflow regressions;
- Gitleaks scans repository history;
- Semgrep provides blocking SAST on pull requests and main;
- Qodana provides scheduled/manual defense-in-depth analysis;
- Dependabot covers Gradle and GitHub Actions;
- CodeQL and Dependency Review are configured and activate when GitHub Advanced Security / GitHub Code Security is available.

See `docs/security/SECURITY_BASELINE.md`, `docs/security/CI_SECURITY.md`, and `docs/release/SIGNING.md`.
