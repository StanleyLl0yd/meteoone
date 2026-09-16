# GitHub signed build for manual RuStore publication

MeteoOne uses GitHub Actions only to produce and verify signed Android artifacts. Upload to RuStore, release creation in RuStore Console, moderation, tester management, and publication remain manual owner actions.

The workflow is `.github/workflows/signed-release-build.yml` (`Signed Android Artifact`). It can run only through `workflow_dispatch` and rejects any source ref other than the current canonical `main`.

## GitHub environment

Create a GitHub Actions environment named `release` and put the signing values below in **environment secrets**. Repository secrets with the same names also resolve through `secrets.*`, but the `release` environment is preferred because it keeps production signing material scoped to the manual release job.

The connected automation used for repository maintenance cannot read or write GitHub secret values. Add/replace the values in GitHub repository settings yourself; never paste them into an issue, PR, commit, Actions input, or chat transcript.

Required secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the **RuStore upload-key** JKS/keystore bytes, with no surrounding quotes. |
| `ANDROID_KEYSTORE_PASSWORD` | Password that opens the upload keystore. |
| `ANDROID_KEY_ALIAS` | Alias of the upload private key inside the keystore. |
| `ANDROID_KEY_PASSWORD` | Password for that private-key entry. |
| `ANDROID_UPLOAD_CERT_SHA256` | Expected SHA-256 fingerprint of the upload certificate. Colons and letter case are accepted. |

The JKS used by this workflow is the key that signs the AAB uploaded manually to RuStore. Keep its role distinct from the long-lived application-signing key imported/configured in RuStore for APKs delivered to users. See [SIGNING.md](SIGNING.md).

## Preparing the values locally

Do this on a trusted machine. The examples intentionally do not put passwords on the command line.

Base64-encode the keystore for `ANDROID_KEYSTORE_BASE64`:

```text
base64 < /secure/path/meteoone-rustore-upload.jks | tr -d '\n'
```

Read the certificate fingerprint and confirm the alias interactively:

```text
keytool -list -v -keystore /secure/path/meteoone-rustore-upload.jks -alias YOUR_ALIAS
```

Copy the `SHA256:` certificate fingerprint into `ANDROID_UPLOAD_CERT_SHA256`. After the first independently verified run, record the same public fingerprint in [CERTIFICATE_FINGERPRINTS.md](CERTIFICATE_FINGERPRINTS.md) through a normal reviewed PR. The fingerprint is public integrity metadata; the keystore and passwords are not.

## What the workflow enforces

Before any signing secret is used, the workflow:

1. requires `refs/heads/main`;
2. verifies that the checked-out SHA exactly equals current `origin/main`;
3. verifies CI supply-chain and release-secret policies;
4. validates package identity, `versionName`, and positive `versionCode`;
5. requires release notes for the exact source version;
6. waits for successful `CI`, `Security and Quality`, and `Secret Scan` **push** runs on that exact `main` SHA.

The signing job then:

1. reconstructs the keystore only under `RUNNER_TEMP` with restrictive permissions;
2. verifies the keystore alias and certificate SHA-256 against `ANDROID_UPLOAD_CERT_SHA256` before building;
3. builds a signed release APK and AAB using the same conditional Gradle signing boundary used in the other StanleyLl0yd Android projects;
4. verifies package name, version code, version name, APK v2/v3 signatures, AAB JAR signature, and certificate fingerprint;
5. verifies the expected arm64 GRIB native libraries are present in both APK and AAB;
6. preserves the release R8 mapping file;
7. generates deterministic `SHA256SUMS` and verifies it;
8. creates GitHub artifact attestations for APK/AAB;
9. uploads the verified files as a GitHub Actions artifact with 30-day retention;
10. deletes the temporary keystore in an `always()` cleanup step.

The job has no repository write permission and contains no RuStore upload/publish step.

## Running a signed build

After this workflow has been merged to `main`, and after the five secrets exist:

1. open GitHub **Actions**;
2. select **Signed Android Artifact**;
3. choose **Run workflow** on `main`;
4. wait for both `Validate signed build request` and `Build and verify signed artifacts` to succeed;
5. download `meteoone-<version>-signed` from the workflow run;
6. verify `SHA256SUMS` after download;
7. upload the `.aab` to RuStore manually.

For the first alpha the expected version is currently `0.1.0-alpha.1`, but the workflow reads the version from the reviewed `app/build.gradle.kts` rather than hard-coding that alpha forever.

## After download

The artifact contains:

- `meteoone-<version>.aab` — upload this manually to RuStore;
- `meteoone-<version>.apk` — supplementary locally installable signed APK for verification/smoke testing;
- `meteoone-<version>-mapping.txt` — matching R8 mapping;
- `SHA256SUMS` — hashes for all three files.

Do not re-sign, modify, zip-repack, or otherwise transform the AAB after this workflow. RuStore must receive the exact AAB whose signature and checksum were verified by the run.
