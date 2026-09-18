# Android release signing and integrity

## Current state

MeteoOne has one manual signed-release path. Every released version creates a GitHub Release containing both signed Android binaries from the same reviewed `main` SHA:

- `meteoone-<version>.apk`;
- `meteoone-<version>.aab`.

Alpha and beta versions are GitHub-only. A future stable AAB may be uploaded to RuStore manually; repository automation never publishes to RuStore.

The Android application ID is permanently `com.sl.meteoone`. Future store distribution must preserve the intended Android signing/update lineage.

The workflow and required repository secrets are documented in [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md).

## Release artifact policy

APK and AAB are always produced together. Manual device testing is optional for creating the GitHub Release itself. Before any RuStore AAB upload, the APK from that same GitHub Release must pass manual device acceptance testing; if the APK fails, do not upload its AAB.

Only APK and AAB are attached to GitHub Releases. Certificates, PEM files, checksum manifests, R8 mappings, provenance files and complete build directories are not published as release assets.

There is no separate APK-only release workflow and no AAB-only release workflow.

## GitHub signing secrets

The `Signed Android Release` workflow reads these Actions repository secrets:

- `ANDROID_KEYSTORE_BASE64`;
- `ANDROID_KEYSTORE_PASSWORD`;
- `ANDROID_KEY_ALIAS`;
- `ANDROID_KEY_PASSWORD`;
- `ANDROID_UPLOAD_CERT_SHA256`.

The fingerprint secret is an internal fail-closed verification input; it is not a GitHub Release asset.

No GitHub Environment is required. Ordinary pull-request and push CI never receives signing values. Never commit or paste keystore bytes, passwords or private keys into Git, issues, PRs, Actions inputs or chat transcripts.

Gradle signing is conditional for ordinary CI. The release workflow sets `REQUIRE_RELEASE_SIGNING=true`, making incomplete signing configuration fatal.

## Signing key policy

Private signing keys are never committed to Git. Keep the original keystore and credentials backed up securely outside the development machine.

The current signing identity signs both GitHub-release APK and AAB outputs. Store-specific application-signing/upload-key roles are deliberately deferred until a stable release is selected for RuStore under M7; do not infer store configuration from the GitHub release key alone.

## Trusted release controls

Before signing material is used, the workflow:

1. requires the current canonical `main` branch and exact current `origin/main` SHA;
2. validates application identity, version metadata, release notes, CI supply-chain policy and release-secret policy;
3. rejects an already existing `v<versionName>` tag or GitHub Release;
4. requires successful `CI`, `Security and Quality`, and `Secret Scan` push runs on that exact source SHA.

The signing job then:

1. reconstructs a temporary keystore under `RUNNER_TEMP` with restrictive permissions;
2. verifies its alias and certificate against the configured expected fingerprint;
3. builds signed release APK and AAB with `REQUIRE_RELEASE_SIGNING=true`;
4. verifies APK/AAB signatures, package/version identity and required native libraries;
5. calculates hashes in logs as an internal integrity check but does not publish checksum files;
6. creates GitHub Release `v<versionName>` targeting the exact source SHA;
7. marks prerelease version names as GitHub prereleases;
8. uploads exactly APK and AAB as release assets;
9. removes temporary signing material unconditionally.

The workflow receives repository `contents: write` only because creating the release/tag requires it. It has no RuStore credentials.

## Release tags

Every GitHub Release uses tag `v<versionName>`. Existing release tags must never be moved, replaced or reused. A version name is therefore single-use.

Version codes must also increase monotonically across released Android builds, including alpha and beta builds, so direct APK upgrades and later store publication retain a valid Android upgrade path.

## Stable store handoff

When M7 selects a stable version for RuStore:

1. create the normal GitHub Release first;
2. install the APK from that exact release and complete the manual acceptance test;
3. if the APK test fails, do not upload the AAB; fix the release and create a new monotonic version pair;
4. after the APK passes, take the AAB directly from that same release without modifying or re-signing it;
5. complete the then-current RuStore signing/metadata process manually;
6. upload the unchanged AAB manually.

Alpha and beta releases never enter this store handoff.
