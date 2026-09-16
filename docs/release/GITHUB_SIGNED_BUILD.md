# GitHub signed build for manual RuStore publication

MeteoOne uses GitHub Actions only to produce and verify a signed Android App Bundle for manual RuStore publication. Upload to RuStore, release creation in RuStore Console, moderation, tester management, and publication remain manual owner actions.

The signing workflow is `.github/workflows/signed-release-build.yml` (`Signed Android Artifact`). It can run only through `workflow_dispatch` and rejects any source ref other than the current canonical `main`.

For the first closed-alpha release, `.github/workflows/signed-release-request.yml` (`Signed Android Artifact Request`) provides a narrow owner-only request bridge: a new comment whose body is exactly `/build-signed-alpha` on release issue `#153`, authored by the repository owner, dispatches the existing signing workflow on `main`. The bridge receives no signing secrets and has only `actions: write`; it cannot publish to RuStore, write repository contents, create tags, or create releases. All other issue comments make its job skip.

## GitHub repository secrets

Store the signing values below as **Actions repository secrets** for `StanleyLl0yd/meteoone`. The manual signing workflow references them explicitly through `secrets.*`; no GitHub Environment is required.

This matches the simpler repository-secret pattern already used by the neighboring `StanleyLl0yd/biorhythms` release workflow. Other neighboring projects also use the same four core secret names, with some additionally placing their jobs behind a `release` Environment. MeteoOne deliberately does not require that extra Environment because RuStore publication itself remains an owner-only manual action and this workflow has no store-publish or repository-write path.

The connected automation used for repository maintenance cannot read GitHub secret values. Add or replace the values in GitHub repository settings or with authenticated GitHub CLI tooling on a trusted machine; never paste passwords, keystore bytes, private keys, PEPK exports, or recovery material into an issue, PR, commit, Actions input, or chat transcript.

Required secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the JKS/keystore bytes used to sign the RuStore upload AAB, with no surrounding quotes. |
| `ANDROID_KEYSTORE_PASSWORD` | Password that opens the keystore. |
| `ANDROID_KEY_ALIAS` | Alias of the private key inside the keystore. |
| `ANDROID_KEY_PASSWORD` | Password for that private-key entry. |
| `ANDROID_UPLOAD_CERT_SHA256` | Expected SHA-256 fingerprint of the certificate used to sign the upload AAB. Colons and letter case are accepted. |

**Do not infer the RuStore key role merely from the existence of an older Android release key.** RuStore's AAB flow distinguishes the application-signing key from the upload key. If an existing JKS has historically signed distributable APKs, treat it as application-signing material unless the RuStore configuration intentionally registers that same certificate as the AAB upload identity. Prefer a separate upload key when establishing a new managed-signing setup.

## Preparing the values locally

Do this on a trusted machine. The examples intentionally do not put passwords on the command line.

Base64-encode the keystore for `ANDROID_KEYSTORE_BASE64`:

```text
base64 < /secure/path/meteoone-upload.jks | tr -d '\n'
```

Read the certificate fingerprint and confirm the alias interactively:

```text
keytool -list -v -keystore /secure/path/meteoone-upload.jks -alias YOUR_ALIAS
```

Copy the `SHA256:` certificate fingerprint into `ANDROID_UPLOAD_CERT_SHA256`. Record public certificate fingerprints in [CERTIFICATE_FINGERPRINTS.md](CERTIFICATE_FINGERPRINTS.md) through a normal reviewed PR. The fingerprint is public integrity metadata; the keystore and passwords are not.

## What the workflow enforces

Before any signing secret is used, the workflow:

1. requires `refs/heads/main`;
2. verifies that the checked-out SHA exactly equals current `origin/main`;
3. verifies CI supply-chain and release-secret policies;
4. validates package identity, `versionName`, and positive `versionCode`;
5. requires release notes for the exact source version;
6. waits for successful `CI`, `Security and Quality`, and `Secret Scan` **push** runs on that exact `main` SHA.

The signing job then re-checks that the same SHA is still current `origin/main` before touching signing material. It then:

1. reconstructs the keystore only under `RUNNER_TEMP` with restrictive permissions;
2. verifies the keystore alias and certificate SHA-256 against `ANDROID_UPLOAD_CERT_SHA256` before building;
3. sets `REQUIRE_RELEASE_SIGNING=true`, so Gradle fails closed if release signing is not actually wired even when the workflow continues;
4. builds a signed release APK and AAB using the env-driven Gradle signing convention used in the neighboring StanleyLl0yd Android projects;
5. uses the temporary APK only inside the runner to verify package/version, APK v2/v3 signing, certificate identity, and native packaging;
6. verifies the AAB JAR signature, certificate fingerprint, and expected arm64 GRIB native libraries;
7. preserves the release R8 mapping file;
8. writes a public build provenance record with source SHA, Actions run URL, version, application ID, and signing-certificate fingerprint;
9. generates deterministic `SHA256SUMS` for the AAB, mapping, and provenance and verifies it;
10. creates a GitHub artifact attestation for the AAB;
11. uploads only the AAB, mapping, provenance, and checksums as a 30-day GitHub Actions artifact;
12. deletes the temporary keystore in an `always()` cleanup step.

The locally generated APK is deliberately **not** exported from the workflow. An APK installed from RuStore is signed with the application-signing key configured in RuStore's AAB signing flow and may have a different certificate, so exposing the temporary APK as a release artifact would create a misleading installation/update path.

The job has no repository write permission and contains no RuStore upload/publish step.

## Requesting a signed build

After the workflows are merged to `main` and the five repository secrets exist, either of these owner actions dispatches the same `Signed Android Artifact` workflow on `main`:

- GitHub **Actions** → **Signed Android Artifact** → **Run workflow**; or
- comment exactly `/build-signed-alpha` on release issue `#153`.

The issue-comment path is intentionally fixed to the first-alpha release issue and the repository owner. It is not a general command interface and it never receives the signing secrets itself.

After dispatch:

1. require both `Validate signed build request` and `Build and verify signed artifacts` to succeed;
2. download `meteoone-<version>-signed` from the signing workflow run;
3. verify `SHA256SUMS` after download;
4. upload the `.aab` to RuStore manually only after the RuStore upload-certificate role has been confirmed.

For the first alpha the expected version is currently `0.1.0-alpha.1`, but the signing workflow reads the version from the reviewed `app/build.gradle.kts` rather than hard-coding that alpha forever.

## After download

The artifact contains:

- `meteoone-<version>.aab` — upload this exact file manually to RuStore;
- `meteoone-<version>-mapping.txt` — matching R8 mapping;
- `meteoone-<version>-build.txt` — source/run/version/fingerprint provenance;
- `SHA256SUMS` — hashes for those three files.

Do not re-sign, modify, zip-repack, or otherwise transform the AAB after this workflow. RuStore must receive the exact AAB whose signature and checksum were verified by the run.
