# RuStore closed alpha release gate

This document is the repository-side checklist for the first private MeteoOne alpha, `0.1.0-alpha.1`.

It does not replace RuStore Console configuration or developer/legal review. Secrets, keystores and private signing material must never be committed to this repository. RuStore publication is performed manually by the repository owner.

## Immutable application identity

- Application ID: `com.sl.meteoone`
- Version name: `0.1.0-alpha.1`
- Repository version code: `1`
- Primary store: RuStore
- Intended track: private alpha testing
- Primary store artifact: signed AAB

`versionCode = 1` is the repository default for MeteoOne's intended first distributed build because no release lineage exists in Git. Before signing/uploading, the owner must also confirm in RuStore Console that no earlier package/version already occupies `versionCode >= 1`. Store history is external state and cannot be inferred from Git tags. If a previous RuStore upload requires a higher code, stop: raise `versionCode` in a dedicated PR and rerun the complete release gate before producing the distributable artifact.

After a build is submitted/published in a store lineage, every later version must use a higher version code as required by that store.

## Privacy and permission declaration baseline

The store declaration must match the release code and [PRIVACY.md](../../PRIVACY.md).

Current alpha behavior:

- `INTERNET` is used for weather-data requests.
- `ACCESS_COARSE_LOCATION` is requested only after an explicit user action.
- `ACCESS_FINE_LOCATION` is not requested.
- Android-provided location is reduced to the canonical 0.1-degree forecast grid before persistence and before it becomes a forecast/cache identity.
- raw device latitude/longitude is not persisted;
- the active reduced coordinate and time-zone id are stored in DataStore;
- fused forecast snapshots and provenance/freshness state are stored in Room for offline use;
- Android backup is disabled;
- no MeteoOne account, ads, analytics SDK or behavioral-tracking SDK is present in this alpha.

For the RuStore data-safety/permission forms, disclose approximate location conservatively: the app processes approximate location for app functionality and may transmit the privacy-reduced 0.1-degree forecast coordinate to a weather provider when required to obtain the forecast. Do not describe raw/exact device coordinates as stored or transmitted by MeteoOne.

External weather services receive normal HTTPS transport metadata, which can include the connection's public IP address. Their processing is outside MeteoOne's local storage boundary.

## Source and CI gate

Before signing any distributable artifact:

1. source revision is the current canonical `main`;
2. RuStore Console version history is checked and confirms repository `versionCode = 1` is acceptable; otherwise bump it in a new reviewed PR first;
3. `python3 scripts/verify_release_metadata.py --expected-version-name 0.1.0-alpha.1 --expected-version-code 1` passes for the chosen source revision;
4. repository `CI` push run passes, including full Gradle/Android verification, Room schema drift, native AAR, and release JNI/R8 checks;
5. `Security and Quality` push run passes;
6. `Secret Scan` push run passes;
7. Dependency Review passed on the PR that introduced the source/release changes;
8. CodeQL status is recorded honestly; a compatibility-gated skip is not treated as successful Kotlin CodeQL analysis.

The manual [Signed Android Artifact](GITHUB_SIGNED_BUILD.md) workflow independently re-checks current `main` and refuses to expose release secrets until the required push workflows above have succeeded on that exact SHA.

## Signing gate

Follow [SIGNING.md](SIGNING.md), [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md), and [CERTIFICATE_FINGERPRINTS.md](CERTIFICATE_FINGERPRINTS.md).

The owner has reported that signing key material already exists outside Git. Before the first MeteoOne RuStore upload, keep the roles explicit rather than creating/replacing keys blindly:

- identify the long-lived **application-signing key** used for Android update identity;
- identify the certificate RuStore will accept as the **AAB upload key**;
- derive and independently verify SHA-256 public-certificate fingerprints for both roles;
- configure RuStore's application-signing/import side as required by its current AAB flow;
- store only the JKS actually intended to sign the upload AAB in the five GitHub Actions **repository secrets** documented in `GITHUB_SIGNED_BUILD.md`;
- set `ANDROID_UPLOAD_CERT_SHA256` from that JKS certificate fingerprint;
- keep the long-lived application-signing private key out of ordinary Actions when RuStore is configured to use a separate upload key.

The currently observed external JKS certificate is already used by neighboring projects to sign distributable release APKs, so its existence alone does not prove that it is a dedicated RuStore upload identity. If the same certificate is intentionally used for both MeteoOne application signing and RuStore upload, record that decision explicitly in `CERTIFICATE_FINGERPRINTS.md` and in the RuStore configuration.

For the RuStore AAB flow, the GitHub-produced AAB is signed with the certificate registered as the **upload key**. RuStore-generated APKs delivered to users use the configured **application-signing key**. These roles must not be confused when verifying artifacts or recording fingerprints.

The same application-signing identity must be preserved when MeteoOne later enters Google Play. A different store upload key is acceptable; an incompatible application-signing key is not.

No release tag or GitHub Release is required for this first closed-alpha artifact path.

## Signed artifact gate

After the five repository secrets are configured:

1. run **Signed Android Artifact** manually on `main`;
2. require both workflow jobs to succeed;
3. download the `meteoone-<version>-signed` Actions artifact;
4. verify `SHA256SUMS` after download;
5. keep the downloaded `.aab` byte-for-byte unchanged;
6. record the workflow run URL, exact source SHA, AAB SHA-256, and signing-certificate fingerprint for the release record.

The workflow verifies:

- package/application ID `com.sl.meteoone`;
- reviewed version name/code;
- APK v2/v3 signatures;
- AAB JAR signature;
- expected certificate SHA-256;
- required arm64 GRIB native libraries in APK and AAB;
- non-empty matching R8 mapping;
- deterministic checksums;
- GitHub artifact attestation for the AAB.

It then deletes the temporary keystore and only uploads the verified artifacts to the GitHub Actions run. It has no RuStore credentials and performs no store publication.

## Store metadata

Prepare the console content from:

- [RUSTORE_LISTING.md](RUSTORE_LISTING.md) for listing copy and screenshot requirements;
- [0.1.0-alpha.1.md](0.1.0-alpha.1.md) for release notes;
- [PRIVACY.md](../../PRIVACY.md) for the public privacy-policy source.

Recheck the current RuStore documentation and console fields immediately before submission because store requirements can change independently of the repository.

## RuStore Console / external gate

Repository automation intentionally stops before these owner/store actions:

- verify the package/version history before accepting repository `versionCode`;
- create/finish the RuStore application entry and developer/legal/contact details;
- provide a publicly reachable privacy-policy URL based on the reviewed policy in `PRIVACY.md`;
- complete the current RuStore permission and data-safety declarations;
- configure/import the long-lived application-signing key and register the upload-key certificate as required for AAB distribution;
- manually upload the exact verified AAB downloaded from GitHub Actions;
- submit that AAB for alpha moderation;
- add only intended alpha testers through the RuStore testing flow;
- verify installation from the RuStore client on at least one supported device and confirm the delivered APK uses the expected application-signing certificate.

## Alpha acceptance smoke test

After store installation, a tester must be able to:

1. launch the app without an automatic location prompt;
2. explicitly request approximate current location;
3. fetch and display the forecast;
4. kill/restart the process and see the cached forecast without network access;
5. distinguish fresh, stale and expired cached data;
6. trigger a manual online refresh without cached data disappearing during the refresh;
7. retain cached data when a refresh/provider request fails.

M3 product-polish work is not part of this alpha gate.
