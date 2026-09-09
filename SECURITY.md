# Security Policy

MeteoOne is currently private and pre-release.

## Reporting

Do not disclose suspected vulnerabilities in a public issue. Contact the repository owner privately through an established private channel.

## Secrets and signing

The repository must never contain:

- Android keystores or private signing keys;
- signing passwords;
- provider API secrets;
- service-account credentials;
- production environment files.

Release signing material must remain outside Git and be injected only into an explicitly trusted release environment.

## Location privacy

Exact device coordinates are transient data. They must not be persisted, logged, included in analytics, crash reports, issue fixtures, or test snapshots. Persist only the normalized forecast location/grid cell needed for product behavior.

## Dependency and CI security

Dependencies should be pinned through the version catalog and updated deliberately. CI permissions should follow least privilege, and third-party workflow actions should be pinned to immutable revisions where practical.
