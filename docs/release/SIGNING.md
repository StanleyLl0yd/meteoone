# Android release signing and integrity

## Current state

MeteoOne is pre-release and has no GitHub Release pipeline yet. Do not create a privileged signing workflow merely to satisfy a checklist before a real release exists.

The Android application ID is permanently `com.sl.meteoone`. RuStore and future Google Play distribution must preserve one signing lineage.

## Signing key policy

The application signing key is never committed to Git.

Keep the original keystore and credentials backed up securely outside the development machine. Prefer a distinct Google Play upload key later when the platform supports that model, so routine uploads do not expose the long-term application signing key.

Ordinary pull-request CI must never receive production signing material.

## Required controls before the first release

Before publishing the first APK/AAB, implement and verify a trusted release path that:

1. builds from an exact release tag/commit that is already contained in verified `main`;
2. validates semantic version tag, `versionName`, monotonic positive `versionCode`, namespace and `applicationId`;
3. obtains signing credentials only from a protected release environment or equivalently trusted store;
4. reconstructs any temporary keystore with restrictive filesystem permissions;
5. builds signed release APK and AAB;
6. verifies APK/AAB signatures and the expected signing-certificate SHA-256 fingerprint;
7. emits SHA-256 checksums for distributed binaries;
8. preserves the R8 mapping file when minification is enabled;
9. generates GitHub artifact attestations/provenance when available and meaningful;
10. removes temporary signing material even on failure;
11. refuses to overwrite or move an existing release tag/release.

OIDC `id-token: write`, `attestations: write`, and `contents: write` must be scoped only to the release job that genuinely needs them.

## Release tags

When MeteoOne begins using `vX.Y.Z` release tags, configure owner-side rules so existing release tags cannot be deleted or moved. Tag creation must be tied to a verified release intent; tag immutability is required once published.

## Artifacts

A production release should provide:

- signed AAB;
- signed APK;
- SHA-256 checksum manifest;
- R8 mapping file when minification is enabled;
- release notes;
- provenance/attestation when supported.

Do not upload complete build directories or signing material as CI artifacts. Use bounded retention for verification artifacts.
