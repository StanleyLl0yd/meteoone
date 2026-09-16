# RuStore closed alpha release gate

This document is the repository-side checklist for the first private MeteoOne alpha, `0.1.0-alpha.1`.

RuStore publication remains a **manual owner action**. Repository automation may build, sign, verify and attest the AAB, but it must not upload or publish to RuStore. Secrets, keystores, private keys and PEPK output must never be committed.

Current RuStore documentation was rechecked on 2026-09-16:

- closed/private testing uses **alpha testing**;
- alpha supports APK and AAB;
- access is limited to explicitly invited testers identified by VK ID;
- up to 2000 testers can be invited;
- alpha versions are available through the mobile RuStore client, not the web catalog;
- if this is the first app version, the alpha web link may return 404 and that is expected;
- after an alpha consumes a `versionCode`, later alpha/public builds must use a higher code;
- for AAB delivery the developer signs the AAB with the upload key, while RuStore generates APKs and signs them with the configured application-signing key.

Official references:

- <https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/testing/alpha-testing>
- <https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/new-version-app/upload-aab>

## Release identity

- Application ID: `com.sl.meteoone`
- Version name: `0.1.0-alpha.1`
- Repository version code: `1`
- Primary store: RuStore
- Intended track: closed/private alpha testing
- Store artifact: signed AAB
- GitHub tag/release: not required for this first closed alpha

Before store upload, confirm in RuStore Console that no existing MeteoOne package/version history requires `versionCode > 1`. If it does, stop, raise `versionCode` in a reviewed PR, and rerun the signed-artifact gate. If alpha `versionCode = 1` is accepted and used, the next alpha/public build must use a higher version code.

## Privacy and permission declaration baseline

The store declaration must match the release code and [PRIVACY.md](../../PRIVACY.md).

Current alpha behavior:

- `INTERNET` is used for weather-data requests;
- `ACCESS_COARSE_LOCATION` is requested only after explicit user action;
- `ACCESS_FINE_LOCATION` is not requested;
- Android-provided location is reduced to the canonical 0.1-degree forecast grid before persistence and forecast/cache identity;
- raw device latitude/longitude is not persisted;
- reduced coordinate and time-zone id are stored in DataStore;
- fused forecast snapshots plus provenance/freshness are stored in Room for offline use;
- Android backup is disabled;
- no MeteoOne account, ads, analytics SDK or behavioral-tracking SDK is present in this alpha.

For RuStore data-safety/permission forms, disclose approximate location conservatively. MeteoOne may transmit the privacy-reduced 0.1-degree forecast coordinate to weather providers to obtain the forecast. External providers also receive normal HTTPS transport metadata such as the connection's public IP address.

Public privacy-policy URL for the store form:

https://github.com/StanleyLl0yd/meteoone/blob/main/PRIVACY.md

## Repository verification status

Repository signing/build verification is **COMPLETE** for the frozen release source:

- source SHA: `ae27269aa753ebcd812922b5023d5f6854810df9`
- post-merge CI: success
- Security and Quality: success
- Secret Scan: success
- CodeQL: skipped by the existing compatibility gate; not represented as a Kotlin CodeQL pass
- signed-build request run: `35109388774` — success
- `Signed Android Artifact` run: `35109403734` — success
- Actions artifact id: `10451423888`
- artifact archive SHA-256: `6f16da1bd15162166eca2b4c28e6e9cfb4de2c5c150905ebf7e7da131e328cb4`
- AAB SHA-256: `b5da2069b7d08d7eec39882aef921702c145d8648fe082ce6c6d0019b7bcd93c`
- signing/upload-candidate certificate SHA-256: `F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`
- signer: RSA 2048, exactly one signer
- GitHub build-provenance attestation: `47936791`

The workflow verified package/version identity, APK v2/v3 signatures, AAB JAR signature, certificate fingerprint, required arm64 GRIB native libraries, R8 mapping, deterministic checksums, provenance and temporary-keystore cleanup. The downloaded artifact was independently rechecked after Actions.

## Signing / RuStore AAB gate

Follow [SIGNING.md](SIGNING.md), [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md), and [CERTIFICATE_FINGERPRINTS.md](CERTIFICATE_FINGERPRINTS.md).

Keep these roles explicit:

- **application-signing key** — long-lived Android update identity used by RuStore to sign generated APKs delivered to users;
- **upload key** — signs the AAB submitted to RuStore and is authenticated by its public certificate.

The verified AAB currently uses certificate:

`F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`

RuStore Console must still confirm that this certificate is intentionally registered as MeteoOne's AAB upload identity. The application-signing identity must also be configured in RuStore's AAB signing flow. If the same external key is intentionally used for both roles, record that explicitly; otherwise keep the long-lived application-signing key separate from the GitHub upload-key secrets.

RuStore currently asks for both application-signing material and the upload-key certificate during AAB signing setup. The public upload certificate can be exported directly from the verified AAB without accessing private key material:

```text
python3 scripts/export_aab_upload_certificate.py \
  meteoone-0.1.0-alpha.1.aab \
  meteoone-0.1.0-alpha.1-uploadcert.pem \
  --expected-sha256 F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58
```

The PEM is public certificate material. The PEPK/application-signing export remains sensitive and must be produced only with the RuStore Console's current unique encryption key and the intended application-signing keystore.

## Store metadata / media

Prepare console content from:

- [RUSTORE_LISTING.md](RUSTORE_LISTING.md) — listing copy and screenshot plan;
- [0.1.0-alpha.1.md](0.1.0-alpha.1.md) — release notes and immutable build evidence;
- [PRIVACY.md](../../PRIVACY.md) — public privacy-policy source.

For this manual Console release, prepare screenshot files satisfying the strict intersection of current Console and public API requirements: at least 3 screenshots, one orientation, exact 9:16 or 16:9, JPEG/PNG, minimum side 320 px, maximum mobile resolution 2160×3840 px, and maximum 3 MB per phone screenshot. Recheck the Console immediately before upload because store limits can change independently of Git.

Current RuStore publication documentation also requires at least one developer contact field; current choices include email, VK group, website and МАКС.

## Remaining external/manual gate

Repository automation intentionally stops here. Still required in RuStore Console:

1. confirm the MeteoOne package/version history permits `versionCode = 1`;
2. create/finish the app entry and developer/contact/legal information;
3. use the public privacy-policy URL above;
4. complete the current permission/data-safety declarations;
5. provide real release screenshots/listing assets;
6. configure/import the intended application-signing key for AAB delivery;
7. register/confirm the verified AAB upload certificate;
8. manually upload the exact unchanged AAB with SHA-256 `b5da2069b7d08d7eec39882aef921702c145d8648fe082ce6c6d0019b7bcd93c`;
9. submit the alpha for moderation;
10. add intended testers by their matching RuStore/VK ID and share the mobile alpha link;
11. install through the mobile RuStore client;
12. verify the certificate on the RuStore-delivered APK;
13. complete the smoke test below.

Do not use the web catalog as the installation test for a first alpha; RuStore documents that the web link may return 404 for a first alpha.

## Alpha acceptance smoke test

A RuStore-installed tester build must be able to:

1. launch without an automatic location prompt;
2. explicitly request approximate current location;
3. fetch and display the forecast;
4. kill/restart and show the cached forecast without network access;
5. distinguish fresh, stale and expired cached data;
6. manually refresh online without cached data disappearing during refresh;
7. retain cached data when a refresh/provider request fails.

Record the delivered APK application-signing fingerprint and smoke-test result in issue #153. Close #153 only after RuStore acceptance/install and this smoke test succeed.

M3 product-polish work is not part of this alpha gate and must not start before the boundary is resolved.
