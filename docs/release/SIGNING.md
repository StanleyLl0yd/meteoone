# Android release signing and integrity

## Current state

MeteoOne is pre-release. GitHub Actions has a **manual signed-artifact build path**, but no workflow publishes to RuStore and no workflow creates a GitHub Release or release tag.

The Android application ID is permanently `com.sl.meteoone`. RuStore and future Google Play distribution must preserve one application-signing lineage.

The manual workflow and required GitHub secrets are documented in [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md).

## Key roles and cross-store lineage

Do not treat an AAB upload key as the application-signing key.

For the first RuStore AAB release, keep outside Git:

1. a long-lived **application-signing key** controlled by the developer and backed up securely;
2. a separate **RuStore upload key** used to sign the AAB uploaded to RuStore.

Under RuStore's AAB flow, the developer uploads/configures the application-signing key through RuStore's protected signing procedure and registers the public certificate for the upload key. The submitted AAB is signed with the upload key; RuStore-generated APKs delivered to users are signed with the application-signing key.

The long-lived application-signing key chosen for the first RuStore release establishes MeteoOne's Android update identity. Future Google Play onboarding must preserve that identity by importing/using the same application-signing key when the store supports that path; do not silently accept a newly generated incompatible application-signing key. A separate Google Play upload key may be used for routine Play uploads.

Record public SHA-256 certificate fingerprints for the application-signing key and each store upload key in [CERTIFICATE_FINGERPRINTS.md](CERTIFICATE_FINGERPRINTS.md). Never record private key material, certificate files, PEPK exports, or passwords in Git.

## GitHub signing secrets

The `Signed Android Artifact` workflow uses the `release` environment and these explicit secrets:

- `ANDROID_KEYSTORE_BASE64`;
- `ANDROID_KEYSTORE_PASSWORD`;
- `ANDROID_KEY_ALIAS`;
- `ANDROID_KEY_PASSWORD`;
- `ANDROID_UPLOAD_CERT_SHA256`.

The first four names match the established signing convention already used by the neighboring `StanleyLl0yd/biorhythms`, `StanleyLl0yd/password-generator`, and `StanleyLl0yd/watchrelay` Android projects. MeteoOne adds the fifth value so the workflow independently rejects an unexpected upload certificate before and after the build.

Ordinary pull-request and push CI never receives these signing values. Gradle signing is conditional: no signing variables means the existing unsigned release verification path remains available; all four signing variables enables signing; a partial set fails configuration rather than silently producing an unsigned release. The privileged workflow additionally sets `REQUIRE_RELEASE_SIGNING=true`, making a missing signing configuration fatal even if all signing variables disappear together.

## Signing key policy

Application-signing and upload private keys are never committed to Git.

Keep the original application-signing keystore and credentials backed up securely outside the development machine. Keep upload keys separate from the long-lived application-signing key so a routine upload credential can be rotated without changing Android application identity when the store supports rotation.

If the existing key is only the application-signing/APK identity and no separate RuStore upload key exists, do not upload the long-lived private key into GitHub merely to satisfy the workflow. Follow the current RuStore AAB procedure to create/register a separate upload key, then place only that upload-key material in the protected `release` environment.

The `.gitignore` and CI release-secret policy explicitly reject common keystore/PEPK/credential artifacts from repository history. This is defense in depth and does not replace secure off-repository backups.

## Trusted signed-build controls

Before GitHub Actions exposes signing secrets, the manual workflow:

1. requires the current canonical `main` branch and exact current `origin/main` SHA;
2. validates application identity, version metadata, release notes, CI supply-chain policy, and release-secret policy;
3. requires successful `CI`, `Security and Quality`, and `Secret Scan` push runs on that exact source SHA.

After any environment-approval delay, the signing job re-confirms that its source SHA is still current `origin/main`. It then:

1. obtains credentials only from the `release` environment;
2. reconstructs the temporary upload keystore under `RUNNER_TEMP` with restrictive permissions;
3. verifies the upload certificate against the independently configured expected SHA-256 fingerprint;
4. builds signed release APK and AAB artifacts with `REQUIRE_RELEASE_SIGNING=true`;
5. verifies APK/AAB signatures, package/version identity, certificate fingerprint, and expected native libraries;
6. keeps the upload-key-signed APK internal to the runner as a verification artifact only;
7. emits and re-checks deterministic SHA-256 checksums for the distributable AAB, mapping, and public provenance record;
8. preserves the matching R8 mapping file;
9. creates a GitHub artifact attestation for the AAB;
10. uploads only the verified AAB, mapping, provenance, and checksums with bounded retention;
11. removes temporary signing material in an unconditional cleanup step.

The workflow has no repository write permission and no RuStore API credentials. **Publication remains manual.**

## Release tags

The first RuStore closed alpha does not require GitHub to create a release tag. If MeteoOne later begins using `vX.Y.Z` GitHub release tags, configure owner-side rules so existing tags cannot be deleted or moved. A published release tag must become immutable after creation.

## Artifacts

The downloadable manual signed-build artifact provides:

- signed store-upload AAB;
- deterministic `SHA256SUMS`;
- matching R8 mapping file;
- public source/run/version/upload-certificate provenance;
- GitHub artifact attestation for the AAB.

The upload-key-signed APK is intentionally not exported because the APK delivered by RuStore is signed with the application-signing identity and can have a different certificate.

Do not upload complete build directories or signing material as CI artifacts. Use bounded retention for verification artifacts.
