# GitHub signed Android releases

MeteoOne publishes every Android version through a single signed GitHub release pipeline.

Each release always produces exactly two public binary assets from the same reviewed `main` SHA and version:

- `meteoone-<version>.apk` — installable Android package;
- `meteoone-<version>.aab` — Android App Bundle.

The APK is always produced. Manual device testing is optional for creating a GitHub Release and is not a prerequisite for alpha/beta publication there. Alpha and beta versions are distributed through GitHub Releases only. They are not uploaded to RuStore. Before any RuStore AAB upload, the APK from that same GitHub Release must pass manual device acceptance testing; if the APK fails, do not upload its AAB.

For a future stable store release, the AAB from the corresponding GitHub Release may be uploaded to RuStore manually. Repository automation must never upload or publish to RuStore.

The canonical workflow is `.github/workflows/signed-release-build.yml` (`Signed Android Release`). It runs only through `workflow_dispatch`, only from the current canonical `main`, and creates the immutable release identity `v<versionName>`.

`.github/workflows/signed-release-request.yml` provides an owner-only request bridge. An issue comment whose body is exactly `/build-release`, authored by the repository owner, dispatches the canonical workflow on `main`. The bridge receives no signing secrets and has only `actions: write`.

There is intentionally no separate APK release chain: APK and AAB must always come from the same release run.

## Repository secrets

The signing workflow uses these Actions repository secrets:

| Secret | Purpose |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the signing JKS bytes. |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password. |
| `ANDROID_KEY_ALIAS` | Private-key alias. |
| `ANDROID_KEY_PASSWORD` | Private-key password. |
| `ANDROID_UPLOAD_CERT_SHA256` | Expected certificate fingerprint used only for fail-closed verification. |

No GitHub Environment is required. Never paste keystore bytes, passwords or private keys into issues, PRs, commits, Actions inputs or chat transcripts.

## Workflow invariants

Before signing, the workflow:

1. requires `refs/heads/main`;
2. verifies the checked-out SHA exactly equals current `origin/main`;
3. verifies CI supply-chain and release-secret policies;
4. validates application id, `versionName`, positive `versionCode`, and matching `docs/release/<version>.md` release notes;
5. rejects an already existing `v<version>` tag or GitHub Release;
6. waits for successful `CI`, `Security and Quality`, `Secret Scan`, and compiled Kotlin `CodeQL` push runs on that exact main SHA.

The signing job then:

1. reconstructs the keystore only under `RUNNER_TEMP` with restrictive permissions;
2. verifies the configured alias and signing certificate before building;
3. builds both `:app:assembleRelease` and `:app:bundleRelease` with release signing required;
4. verifies APK package/version, v2/v3 signatures, one signer and required arm64 native libraries;
5. verifies the AAB JAR signature, signing identity and the same required native libraries;
6. stages only `meteoone-<version>.apk` and `meteoone-<version>.aab` as public release files;
7. creates GitHub Release `v<version>` targeting the exact source SHA;
8. marks versions containing a prerelease suffix such as `-alpha` or `-beta` as GitHub prereleases;
9. attaches only APK and AAB to the GitHub Release;
10. removes temporary signing material unconditionally.

Hashes may be calculated in workflow logs for internal verification, but checksum files, certificates, PEM files, mappings and provenance files are not published as release assets.

## Release sequence

1. Merge all intended changes to protected `main` and let exact-SHA checks finish successfully.
2. Set a new monotonic `versionCode` and the intended `versionName`; add/update `docs/release/<version>.md`.
3. Trigger `/build-release` from an issue or run `Signed Android Release` manually in Actions.
4. The workflow builds, signs and verifies APK+AAB from the same SHA and creates `v<version>`.
5. Download/install the APK when a manual device test is desired. Skipping that test does not invalidate a GitHub-only alpha/beta release.
6. Alpha/beta stop at GitHub Releases.
7. For a future stable version selected for RuStore, manually test the APK from that exact GitHub Release.
8. If the APK test fails, do not upload its AAB; fix the issue and create a new monotonic release pair.
9. Only after the APK passes, upload the unchanged AAB from that same GitHub Release manually as a separate store action.

Never re-sign, modify or repack either binary after the GitHub Release is created.
