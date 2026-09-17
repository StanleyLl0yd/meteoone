# RuStore closed alpha release gate

This document is the repository-side checklist for the first private MeteoOne alpha, `0.1.0-alpha.1`.

RuStore publication remains a **manual owner action**. Repository automation may build, sign, verify and attest release artifacts, but it must not upload or publish to RuStore. Secrets, keystores, private keys and PEPK output must never be committed.

Current RuStore documentation was rechecked on 2026-09-16:

- closed/private testing uses **alpha testing**;
- alpha accepts AAB;
- access is limited to explicitly invited testers identified by matching VK ID;
- up to 2000 testers can be invited;
- after an alpha consumes a `versionCode`, later alpha/public builds must use a higher code;
- for AAB delivery the developer signs the AAB with the upload key, while RuStore generates APKs and signs them with the configured application-signing key.

Official references:

- <https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/testing/alpha-testing>
- <https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/new-version-app/upload-aab>

## Canonical MeteoOne release sequence

Every release build produces one signed bundle from one reviewed source SHA and version:

1. `meteoone-<version>.apk` — install directly for manual device smoke/acceptance testing;
2. `meteoone-<version>.aab` — upload manually to RuStore only after the APK test passes;
3. matching R8 mapping, public provenance and `SHA256SUMS` covering all release files.

APK and AAB are built, verified and attested in the same `Signed Android Artifact` workflow. There is no separate APK-only release pipeline.

If manual APK testing fails, the AAB is blocked from publication. If the APK test passes, the unchanged AAB from the same release bundle is the RuStore submission artifact.

The direct-test APK is **not** the APK that RuStore generates from the AAB. A second functional smoke test of the AAB or RuStore-generated APK is not part of the normal MeteoOne release gate; the manual signed APK is the pre-publication acceptance artifact.

## Release identity

- Application ID: `com.sl.meteoone`
- Version name: `0.1.0-alpha.1`
- Repository version code: `1`
- Primary store: RuStore
- Intended track: closed/private alpha testing
- Manual test artifact: signed release APK
- Store artifact: signed AAB
- GitHub tag/release: not required for this first closed alpha

Before store upload, confirm in RuStore Console that no existing MeteoOne package/version history requires `versionCode > 1`. If it does, stop, raise `versionCode` in a reviewed PR and produce a new signed release bundle. If alpha `versionCode = 1` is accepted and used, every later alpha/public build must use a higher code.

## Canonical `0.1.0-alpha.1` unified release evidence

The canonical signed release bundle was produced from release source SHA:

`e8035f648476669e9c839a8a510d02250faf4b18`

This is the first MeteoOne release run that emits the manually tested APK role and RuStore AAB role together under one provenance/checksum boundary.

- owner request bridge run: `35197083771` — success;
- `Signed Android Artifact` run: `35197092084` — success;
- Actions artifact id: `10486141848`;
- artifact name: `meteoone-0.1.0-alpha.1-signed-release`;
- artifact archive SHA-256: `6289c2e59fab462cabc118eaee7d2322582748260e6e35b9f30a1a612e4bf095`;
- canonical APK SHA-256: `eefac07c4b929e2886a6412c20e61a8141bd479b01a71a8fdcd0cfb9074e48f3`;
- canonical AAB SHA-256: `604f1987183cdbcc68ac95041c522665c9005a9340934fbea6eba0063194cdac`;
- R8 mapping SHA-256: `d267cd082dd2db7deeffb0845b836ce3dee4f19b918949fa19e64d6369b796e0`;
- APK build-provenance attestation: `48125618`;
- AAB build-provenance attestation: `48125637`;
- package/version: `com.sl.meteoone`, `0.1.0-alpha.1`, versionCode `1`;
- APK v2/v3 signatures verified, exactly one signer;
- AAB JAR signature verified;
- signing certificate SHA-256: `F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`;
- all required arm64 GRIB native libraries verified in both APK and AAB;
- `SHA256SUMS` verified for APK, AAB, mapping and provenance;
- temporary signing material removed successfully.

Canonical provenance records:

```text
apk_role=manual-device-smoke-test
aab_role=manual-rustore-upload-after-apk-pass
not_rustore_delivered_apk=true
```

This unified bundle supersedes the earlier AAB-only artifact as the RuStore submission candidate.

## Manual device acceptance result — PASS

The first manual acceptance run used the earlier signed APK built from application source SHA:

`ae27269aa753ebcd812922b5023d5f6854810df9`

Historical tested-APK evidence:

- signed APK workflow run: `35194311112` — success;
- Actions artifact id: `10485222565`;
- tested APK SHA-256: `9a76361306b29252370cba3e40d8e6ee89c1cd32c103f3b4c99ac445028d5e6c`;
- package/version: `com.sl.meteoone`, `0.1.0-alpha.1`, versionCode `1`;
- APK v2/v3 signatures verified, exactly one signer;
- expected signing certificate and required arm64 native libraries verified;
- downloaded artifact checksum verified independently;
- Google Drive `Exchange` upload was read back and matched the same byte size/SHA-256.

On 2026-09-17 that APK passed the complete requested manual device sequence:

1. launch without an automatic location prompt — PASS;
2. explicit approximate-location request — PASS;
3. online forecast fetch/display — PASS;
4. normal forecast content/scrolling — PASS;
5. full app close/restart — PASS;
6. restart with network disabled and cached forecast restored — PASS;
7. offline refresh failure does not erase cached forecast — PASS;
8. network restored and refresh succeeds while old cache remains visible until replacement — PASS;
9. resulting behavior matches the intended M2 offline-first design — PASS.

### Verified transfer of PASS to the canonical unified bundle

The historical tested APK and canonical unified APK are not byte-identical archives because Android embeds Git revision metadata. The functional payload equivalence was checked explicitly:

- Git compare from `ae27269aa753ebcd812922b5023d5f6854810df9` to `e8035f648476669e9c839a8a510d02250faf4b18` contains only release workflow/docs/helper/test changes and no production Android application/module/build-config changes;
- both APKs contain the same 237 ZIP entry names;
- 236/237 APK entry contents are byte-identical;
- the sole changed APK entry is `META-INF/version-control-info.textproto`, whose recorded revision changed from the historical source SHA to the canonical release SHA;
- the AAB functional payload is likewise identical; its differences are limited to the corresponding VCS entry plus JAR signature metadata recalculated because that signed metadata changed;
- historical and canonical R8 mapping files are byte-identical with SHA-256 `d267cd082dd2db7deeffb0845b836ce3dee4f19b918949fa19e64d6369b796e0`;
- required arm64 native payload is present in both canonical binaries.

Therefore the canonical unified release carries the same executable/resource payload that passed manual device acceptance. The `0.1.0-alpha.1` functional acceptance gate remains **PASS** without repeating the device test solely because provenance metadata changed.

For every future release, test the exact APK from that release's unified APK+AAB bundle before uploading its paired AAB.

## Historical first-alpha artifacts

The following artifacts remain provenance history but are no longer the canonical RuStore upload candidate:

- old AAB run `35109403734`, artifact `10451423888`, AAB SHA-256 `b5da2069b7d08d7eec39882aef921702c145d8648fe082ce6c6d0019b7bcd93c`, attestation `47936791`;
- old device-test APK run `35194311112`, artifact `10485222565`, APK SHA-256 `9a76361306b29252370cba3e40d8e6ee89c1cd32c103f3b4c99ac445028d5e6c`.

Do not upload the superseded old AAB when the canonical unified AAB is available.

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

For RuStore data-safety/permission forms, disclose approximate location conservatively. MeteoOne may transmit the privacy-reduced 0.1-degree forecast coordinate to weather providers to obtain the forecast. External providers also receive ordinary HTTPS transport metadata such as the connection's public IP address.

Public privacy-policy URL for the store form:

https://github.com/StanleyLl0yd/meteoone/blob/main/PRIVACY.md

## Signing / RuStore AAB gate

Keep these roles explicit:

- **application-signing key** — long-lived Android update identity used by RuStore to sign generated APKs delivered to users;
- **upload key** — signs the AAB submitted to RuStore and is authenticated by its public certificate.

The canonical first-alpha AAB uses certificate:

`F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`

RuStore Console must confirm that this certificate is intentionally registered as MeteoOne's AAB upload identity. The intended application-signing identity must also be configured in RuStore's AAB signing flow. If the same external key is intentionally used for both roles, record that explicitly; otherwise keep the long-lived application-signing key separate from ordinary GitHub upload-key secrets.

The public upload certificate can be exported from the verified AAB without accessing private key material using `scripts/export_aab_upload_certificate.py`. PEPK/application-signing export remains sensitive and must use the current RuStore Console encryption material.

## Store metadata / media

Prepare Console content from:

- [RUSTORE_LISTING.md](RUSTORE_LISTING.md) — listing copy and screenshot plan;
- [0.1.0-alpha.1.md](0.1.0-alpha.1.md) — release notes and canonical build evidence;
- [PRIVACY.md](../../PRIVACY.md) — public privacy-policy source.

For this manual Console release, prepare at least 3 real screenshots, one orientation, exact 9:16 or 16:9, JPEG/PNG, minimum side 320 px, maximum mobile resolution 2160×3840 px, and maximum 3 MB per phone screenshot. Recheck the Console immediately before upload because store limits can change independently of Git.

At least one developer contact field must be populated in the current Console.

## Remaining external/manual gate

Repository signing/build work and functional device acceptance are complete. Remaining work is RuStore-side only:

1. confirm the MeteoOne package/version history permits `versionCode = 1`;
2. create/finish the app entry and developer/contact/legal information;
3. use the public privacy-policy URL above;
4. complete the current permission/data-safety declarations;
5. provide real release screenshots/listing assets;
6. configure/import the intended application-signing key for AAB delivery;
7. register/confirm the verified AAB upload certificate;
8. manually upload the exact canonical AAB with SHA-256 `604f1987183cdbcc68ac95041c522665c9005a9340934fbea6eba0063194cdac`;
9. submit the alpha for moderation;
10. complete the intended closed-alpha tester configuration/publication state.

No second functional test of the AAB/RuStore-generated APK is required for this release.

## Exit criteria

Close release issue #153 after:

- RuStore confirms the intended version code/signing setup;
- the canonical AAB is accepted for the private alpha track;
- required Console metadata/declarations/assets are complete;
- the alpha has passed RuStore moderation/acceptance for the intended track.

The manual functional acceptance test is already PASS and is not repeated for the AAB.

M3 product-polish work is not part of this alpha gate and must not start before the release boundary is resolved.
