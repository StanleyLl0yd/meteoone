# RuStore closed alpha release gate

This document is the repository-side checklist for the first private MeteoOne alpha, `0.1.0-alpha.1`.

It does not replace RuStore Console configuration or developer/legal review. Secrets, keystores and private signing material must never be committed to this repository.

## Immutable application identity

- Application ID: `com.sl.meteoone`
- Version name: `0.1.0-alpha.1`
- Version code: `1`
- Primary store: RuStore
- Intended track: private alpha testing
- Primary store artifact: signed AAB

`versionCode = 1` is intentional for the first distributed MeteoOne build. It must only increase after a version with code 1 has been submitted/published in a store lineage.

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

1. source revision is contained in protected `main`;
2. `python3 scripts/verify_release_metadata.py --expected-version-name 0.1.0-alpha.1 --expected-version-code 1` passes;
3. repository CI passes, including full Gradle/Android verification;
4. Room schema drift verification passes;
5. vendored native AAR verification passes;
6. release JNI/R8 boundary verification passes;
7. Semgrep/Security and Quality passes according to repository policy;
8. Gitleaks/Secret Scan passes;
9. Dependency Review passes on the release-prep PR;
10. CodeQL status is recorded honestly; a compatibility-gated skip is not treated as successful Kotlin CodeQL analysis.

## Signing gate

Follow [SIGNING.md](SIGNING.md).

The first RuStore AAB establishes MeteoOne's long-term Android update identity. Before the first signed upload, provision outside Git:

- one long-lived **application-signing key** controlled and backed up by the developer;
- one separate **RuStore upload key** for signing AAB files submitted to RuStore;
- the SHA-256 public-certificate fingerprint for both key roles;
- RuStore's required protected import package for the application-signing key;
- the public PEM certificate for the RuStore upload key;
- a protected release execution environment or equivalently trusted local/offline signing procedure;
- immutable protection for published `refs/tags/v*` release tags.

For the RuStore AAB flow, the developer-supplied AAB is signed with the **upload key**. RuStore-generated APKs delivered to users are signed with the imported **application-signing key**. These roles must not be confused when verifying artifacts or recording fingerprints.

The same application-signing identity must be preserved when MeteoOne later enters Google Play. A different store upload key is acceptable; an incompatible application-signing key is not.

Do not create a release tag, GitHub Release, or store upload until these controls exist.

## Artifact verification

For the exact signed AAB intended for RuStore:

- confirm package/application ID is `com.sl.meteoone`;
- confirm version name is `0.1.0-alpha.1`;
- confirm version code is `1`;
- verify the AAB is signed by the expected **RuStore upload-key** certificate;
- separately record/verify the long-lived **application-signing** certificate configured for RuStore-generated APKs;
- verify the native `arm64-v8a` payload expected by the forecast data layer;
- retain the matching R8 mapping file;
- produce and record a SHA-256 checksum for the signed AAB;
- record the exact source commit and build provenance;
- ensure no keystore, password, private key or temporary signing material is present in build artifacts or logs.

## Store metadata

Prepare the console content from:

- [RUSTORE_LISTING.md](RUSTORE_LISTING.md) for listing copy and screenshot requirements;
- [0.1.0-alpha.1.md](0.1.0-alpha.1.md) for release notes;
- [PRIVACY.md](../../PRIVACY.md) for the public privacy-policy source.

Recheck the current RuStore documentation and console fields immediately before submission because store requirements can change independently of the repository.

## RuStore Console / external gate

Repository automation cannot complete these owner/store actions:

- create/finish the RuStore application entry and developer/legal/contact details;
- provide a publicly reachable privacy-policy URL based on the reviewed policy in `PRIVACY.md`;
- complete the current RuStore permission and data-safety declarations;
- configure/import the long-lived application-signing key and register the separate upload-key certificate as required for AAB distribution;
- upload the exact verified upload-key-signed AAB and submit it for alpha moderation;
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
