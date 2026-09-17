# GitHub signed release build for manual testing and RuStore publication

MeteoOne uses one manual GitHub Actions release pipeline to produce the two Android artifacts for the same reviewed source SHA and version:

- a signed **APK** for direct manual device acceptance testing;
- a signed **AAB** for manual RuStore upload only after the APK test passes.

RuStore publication remains a **manual owner action**. Repository automation never uploads or publishes to RuStore, creates a RuStore release, or manages testers.

The canonical workflow is `.github/workflows/signed-release-build.yml` (`Signed Android Artifact`). It can run only through `workflow_dispatch` and rejects any source ref other than the current canonical `main`.

For the first closed-alpha release, `.github/workflows/signed-release-request.yml` (`Signed Android Artifact Request`) provides a narrow owner-only request bridge: a comment whose body is exactly `/build-signed-alpha` on release issue `#153`, authored by the repository owner, dispatches the same signing workflow on `main`. The bridge receives no signing secrets and has only `actions: write`.

There is intentionally **no separate signed-test-APK workflow**. APK and AAB must come from the same release run so their source SHA, version, signing identity, checksums and provenance cannot drift independently.

## GitHub repository secrets

Store the following as Actions **repository secrets** for `StanleyLl0yd/meteoone`:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the release/upload JKS bytes. |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password. |
| `ANDROID_KEY_ALIAS` | Private-key alias. |
| `ANDROID_KEY_PASSWORD` | Private-key password. |
| `ANDROID_UPLOAD_CERT_SHA256` | Expected SHA-256 certificate fingerprint. Colons and letter case are accepted. |

No GitHub Environment is required. Never paste keystore bytes, passwords, private keys, PEPK exports or recovery material into issues, PRs, commits, Actions inputs or chat transcripts.

RuStore's AAB flow distinguishes the **application-signing key** used for store-delivered APKs from the **upload key** used to authenticate the submitted AAB. The certificate used by this workflow must be intentionally registered for the RuStore upload role before the AAB is submitted. If the same external key is deliberately used for both roles, record that explicitly; do not infer it merely from an old JKS filename or historical APK use.

## What the workflow enforces

Before signing material is used, the workflow:

1. requires `refs/heads/main`;
2. verifies the checked-out SHA exactly equals current `origin/main`;
3. verifies CI supply-chain and release-secret policies;
4. validates application identity, `versionName`, positive `versionCode`, and matching release notes;
5. waits for successful `CI`, `Security and Quality`, and `Secret Scan` push runs on that exact `main` SHA.

The signing job re-confirms the same SHA is still current `origin/main`, then:

1. reconstructs the keystore only under `RUNNER_TEMP` with restrictive permissions;
2. verifies alias and certificate SHA-256 before building;
3. sets `REQUIRE_RELEASE_SIGNING=true` so signing fails closed;
4. builds `assembleRelease` and `bundleRelease` in the same run;
5. verifies APK package/version, v2/v3 signatures, exactly one signer, certificate fingerprint and arm64 native payload;
6. verifies AAB JAR signature, certificate fingerprint and the same required native payload;
7. stages `meteoone-<version>.apk` and `meteoone-<version>.aab` together;
8. preserves the matching R8 mapping file;
9. writes one provenance record containing the common source SHA, workflow run, package/version, signing fingerprint and the distinct APK/AAB roles;
10. generates and re-checks one deterministic `SHA256SUMS` covering APK, AAB, mapping and provenance;
11. creates GitHub attestations for both APK and AAB;
12. uploads one 30-day Actions artifact named `meteoone-<version>-signed-release`;
13. removes the temporary keystore in an unconditional cleanup step.

The workflow has no repository-write permission and no RuStore API credentials.

## Canonical release sequence

For every release:

1. run `Signed Android Artifact` from reviewed `main`;
2. require both workflow jobs to succeed;
3. download `meteoone-<version>-signed-release`;
4. verify `SHA256SUMS` after download;
5. install **`meteoone-<version>.apk`** directly on the test device;
6. complete the release smoke/acceptance test;
7. if the APK test fails, do **not** upload the AAB — fix the release, review the change, and create a new release bundle;
8. if the APK test passes, manually upload the unchanged **`meteoone-<version>.aab`** to RuStore.

The APK is a manual pre-publication test artifact. It is **not** the APK that RuStore will generate and deliver from the AAB, and it must not be uploaded to RuStore as the store artifact. A second functional smoke test of the AAB/RuStore-generated APK is not part of the normal MeteoOne release gate; the pre-publication APK test is the acceptance test for the shared release source.

## Artifact contents

The canonical release artifact contains:

- `meteoone-<version>.apk` — install this for manual release testing;
- `meteoone-<version>.aab` — upload this exact file manually to RuStore after APK PASS;
- `meteoone-<version>-mapping.txt` — matching R8 mapping;
- `meteoone-<version>-build.txt` — source/run/version/fingerprint and artifact-role provenance;
- `SHA256SUMS` — hashes for all four files above.

Do not re-sign, modify or repack the APK or AAB after the workflow. The tested APK and uploaded AAB must remain tied to the same recorded release run/source/version.
