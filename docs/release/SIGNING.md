# Android release signing and integrity

## Current state

MeteoOne is pre-release and has no GitHub Release pipeline yet. Do not create a privileged signing workflow merely to satisfy a checklist before a real release exists.

The Android application ID is permanently `com.sl.meteoone`. RuStore and future Google Play distribution must preserve one application-signing lineage.

## Key roles and cross-store lineage

Do not treat an AAB upload key as the application-signing key.

For the first RuStore AAB release, provision outside Git:

1. a long-lived **application-signing key** controlled by the developer and backed up securely;
2. a separate **RuStore upload key** used to sign the AAB uploaded to RuStore.

Under RuStore's AAB flow, the developer uploads the application-signing key through RuStore's protected signing-import procedure and uploads the public certificate for the upload key. The submitted AAB is signed with the upload key; RuStore-generated APKs delivered to users are signed with the application-signing key.

The long-lived application-signing key chosen for the first RuStore release establishes MeteoOne's Android update identity. Future Google Play onboarding must preserve that identity by importing/using the same application-signing key when the store supports that path; do not silently accept a newly generated incompatible application-signing key. A separate Google Play upload key may be used for routine Play uploads.

Record public SHA-256 certificate fingerprints for the application-signing key and each store upload key in [CERTIFICATE_FINGERPRINTS.md](CERTIFICATE_FINGERPRINTS.md). Never record private key material, certificate files, PEPK exports, or passwords in Git.

## Signing key policy

Application-signing and upload private keys are never committed to Git.

Keep the original application-signing keystore and credentials backed up securely outside the development machine. Keep upload keys separate from the long-lived application-signing key so a routine upload credential can be rotated without changing Android application identity when the store supports rotation.

Ordinary pull-request CI must never receive production signing material.

## Required controls before the first release

Before publishing the first APK/AAB, implement and verify a trusted release path that:

1. builds from an exact release tag/commit that is already contained in verified `main`;
2. validates semantic version tag, `versionName`, monotonic positive `versionCode`, namespace and `applicationId`;
3. obtains signing credentials only from a protected release environment or equivalently trusted store;
4. reconstructs any temporary keystore with restrictive filesystem permissions;
5. builds the intended signed upload artifact and any supplementary signed APK;
6. verifies artifact signatures and the expected upload/application certificate SHA-256 fingerprints for their respective roles;
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

- the signed store-upload AAB;
- a signed APK when a supplementary/direct APK is intentionally distributed;
- SHA-256 checksum manifest;
- R8 mapping file when minification is enabled;
- release notes;
- provenance/attestation when supported.

Do not upload complete build directories or signing material as CI artifacts. Use bounded retention for verification artifacts.
