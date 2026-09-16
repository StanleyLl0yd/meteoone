# GitHub signed build for manual RuStore publication

MeteoOne uses GitHub Actions only to produce and verify a signed Android App Bundle for manual RuStore publication. Upload to RuStore, release creation in RuStore Console, moderation, tester management, and publication remain manual owner actions.

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

**Do not put the long-lived application-signing key into these GitHub secrets merely because it is the key you already have.** RuStore's AAB flow distinguishes the application-signing key from the upload key. If the existing key is only the APK/application-signing identity and no upload key exists yet, keep that private key outside ordinary Actions and create/register a separate upload key according to the current RuStore procedure. If the existing key is already the registered RuStore upload key, use that JKS here.

## Preparing the values locally

Do this on a trusted machine. The examples intentionally do not put passwords on the command line.

Base64-encode the upload keystore for `ANDROID_KEYSTORE_BASE64`:

```text
base64 < /secure/path/meteoone-rustore-upload.jks | tr -d '\n'
```

Read the upload certificate fingerprint and confirm the alias interactively:

```text
keytool -list -v -keystore /secure/path/meteoone-rustore-upload.jks -alias YOUR_UPLOAD_ALIAS
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

The signing job then re-checks that the same SHA is still current `origin/main` before touching signing material. It then:

1. reconstructs the upload keystore only under `RUNNER_TEMP` with restrictive permissions;
2. verifies the keystore alias and certificate SHA-256 against `ANDROID_UPLOAD_CERT_SHA256` before building;
3. sets `REQUIRE_RELEASE_SIGNING=true`, so Gradle fails closed if release signing is not actually wired even when the workflow continues;
4. builds a signed release APK and AAB using the env-driven Gradle signing convention used in the neighboring StanleyLl0yd Android projects;
5. uses the temporary APK only inside the runner to verify package/version, APK v2/v3 signing, certificate identity, and native packaging;
6. verifies the AAB JAR signature, upload-certificate fingerprint, and expected arm64 GRIB native libraries;
7. preserves the release R8 mapping file;
8. writes a public build provenance record with source SHA, Actions run URL, version, application ID, and upload-certificate fingerprint;
9. generates deterministic `SHA256SUMS` for the AAB, mapping, and provenance and verifies it;
10. creates a GitHub artifact attestation for the AAB;
11. uploads only the AAB, mapping, provenance, and checksums as a 30-day GitHub Actions artifact;
12. deletes the temporary keystore in an `always()` cleanup step.

The upload-key-signed APK is deliberately **not** exported from the workflow. An APK installed from RuStore is signed with the application-signing key and may have a different certificate, so exposing the temporary upload-key APK as a release artifact would create a misleading installation/update path.

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

- `meteoone-<version>.aab` — upload this exact file manually to RuStore;
- `meteoone-<version>-mapping.txt` — matching R8 mapping;
- `meteoone-<version>-build.txt` — source/run/version/fingerprint provenance;
- `SHA256SUMS` — hashes for those three files.

Do not re-sign, modify, zip-repack, or otherwise transform the AAB after this workflow. RuStore must receive the exact AAB whose signature and checksum were verified by the run.
