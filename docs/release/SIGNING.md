# Android release signing and integrity

## Current state

MeteoOne is pre-release. GitHub Actions has one **manual signed-release build path**. No workflow publishes to RuStore and no workflow creates a GitHub Release or release tag.

The Android application ID is permanently `com.sl.meteoone`. RuStore and future Google Play distribution must preserve one application-signing lineage.

The manual workflow and required GitHub secrets are documented in [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md).

## Release artifact policy

One canonical signed release run produces both Android binaries from the same reviewed source SHA and version:

- signed APK — manual device smoke/acceptance testing before publication;
- signed AAB — manual RuStore upload after the APK test passes.

The release artifact also contains the matching R8 mapping, public build provenance and one `SHA256SUMS` covering APK, AAB, mapping and provenance. Both APK and AAB receive GitHub attestations.

There is no separate APK-only release workflow. A failed manual APK acceptance test blocks the AAB from being uploaded. A passing APK acceptance test authorizes manual upload of the unchanged AAB from the same release bundle.

The manual APK is not the APK generated and delivered by RuStore from the AAB. MeteoOne does not require a second functional smoke test of the RuStore-generated APK as part of the normal release gate.

## Key roles and cross-store lineage

Do not treat an AAB upload key as the application-signing key.

For RuStore AAB distribution, keep outside Git:

1. a long-lived **application-signing key** controlled by the developer and backed up securely;
2. a **RuStore upload key** used to sign the AAB uploaded to RuStore. Prefer keeping it separate from the application-signing key when establishing a new managed-signing setup.

Under RuStore's AAB flow, the submitted AAB is signed with the upload key while RuStore-generated APKs delivered to users are signed with the configured application-signing key.

The long-lived application-signing key chosen for the first RuStore release establishes MeteoOne's Android update identity. Future Google Play onboarding must preserve that identity where the store supports importing/using it; do not silently accept an incompatible application-signing lineage.

Record public SHA-256 certificate fingerprints for the application-signing key and each store upload key in [CERTIFICATE_FINGERPRINTS.md](CERTIFICATE_FINGERPRINTS.md). Never record private key material, certificate files, PEPK exports or passwords in Git.

## GitHub signing secrets

The `Signed Android Artifact` workflow reads these explicit Actions repository secrets:

- `ANDROID_KEYSTORE_BASE64`;
- `ANDROID_KEYSTORE_PASSWORD`;
- `ANDROID_KEY_ALIAS`;
- `ANDROID_KEY_PASSWORD`;
- `ANDROID_UPLOAD_CERT_SHA256`.

No GitHub Environment is required. Ordinary pull-request and push CI never receives these signing values.

Gradle signing is conditional: no signing variables means the unsigned CI verification path remains available; all four signing variables enable release signing; a partial set fails configuration. The manual workflow additionally sets `REQUIRE_RELEASE_SIGNING=true`, making missing signing configuration fatal.

The same Actions signing material signs the direct-test APK and the RuStore-upload AAB because they are two outputs of one release build. That does **not** make the manual APK a RuStore-delivered APK and does not change RuStore's separate application-signing role.

## Signing key policy

Application-signing and upload private keys are never committed to Git.

Keep the original application-signing keystore and credentials backed up securely outside the development machine. Keep upload keys separate from the long-lived application-signing key when establishing a new store-managed signing setup so a routine upload credential can be rotated without changing Android application identity when supported by the store.

An older release JKS that has signed distributable APKs is evidence of an existing release-signing identity, not by itself proof that it is the intended RuStore AAB upload key. If the owner intentionally registers the same certificate for both roles, record that decision explicitly rather than assuming it from the JKS filename or alias.

The `.gitignore` and CI release-secret policy reject common keystore/PEPK/credential artifacts from repository history. This is defense in depth and does not replace secure off-repository backups.

## Trusted signed-build controls

Before GitHub Actions exposes signing secrets, the manual workflow:

1. requires the current canonical `main` branch and exact current `origin/main` SHA;
2. validates application identity, version metadata, release notes, CI supply-chain policy and release-secret policy;
3. requires successful `CI`, `Security and Quality`, and `Secret Scan` push runs on that exact source SHA.

The signing job then re-confirms that its source SHA is still current `origin/main`. It then:

1. reconstructs a temporary keystore under `RUNNER_TEMP` with restrictive permissions;
2. verifies the certificate against the independently configured expected SHA-256 fingerprint;
3. builds signed release APK and AAB artifacts with `REQUIRE_RELEASE_SIGNING=true`;
4. verifies APK/AAB signatures, package/version identity, certificate fingerprint and required native libraries;
5. exports both verified binaries in one release bundle;
6. emits and re-checks deterministic SHA-256 checksums for APK, AAB, mapping and public provenance;
7. preserves the matching R8 mapping file;
8. creates GitHub attestations for both APK and AAB;
9. removes temporary signing material in an unconditional cleanup step.

The workflow has no repository write permission and no RuStore API credentials. **Publication remains manual.**

## Release tags

The first RuStore closed alpha does not require GitHub to create a release tag. If MeteoOne later begins using `vX.Y.Z` GitHub release tags, configure owner-side rules so existing tags cannot be deleted or moved. A published release tag must become immutable after creation.

## Artifacts

The downloadable canonical release artifact provides:

- signed APK for manual pre-publication device testing;
- signed AAB for manual RuStore upload after APK PASS;
- deterministic `SHA256SUMS` covering both binaries plus mapping/provenance;
- matching R8 mapping file;
- public source/run/version/signing-certificate/artifact-role provenance;
- GitHub attestations for APK and AAB.

Do not upload complete build directories or signing material as CI artifacts. Use bounded retention for verification artifacts.
