# MeteoOne release certificate fingerprints

Status: **CANONICAL SIGNED APK+AAB CERTIFICATE VERIFIED — RuStore role assignment still requires store-side confirmation.**

This file is a public integrity record only. Never commit a keystore, private key, password, PEPK export, secret or recovery material here or elsewhere in the repository.

An existing JKS was inspected locally on 2026-09-16 and then exercised by real GitHub signed-release workflows. It contains one private-key entry with alias `key0` and certificate SHA-256:

`F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`

The same public certificate fingerprint is already used by neighboring StanleyLl0yd Android projects for release APK signing. That establishes this certificate as an existing release-signing identity in the owner's Android project set; it does **not** by itself prove that RuStore has registered it as MeteoOne's AAB upload certificate or application-signing certificate.

## Existing external release certificate

- Alias observed locally / used by Actions: `key0`
- SHA-256 certificate fingerprint: `F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`
- Private-key entry count observed: `1`
- Key algorithm / size: `RSA 2048`
- Subject observed by `apksigner`: `C=RU, ST=Saint-Petersburg, L=Saint-Petersburg, O=Silver Lightning, OU=SL, CN=Stanley Lloyd`
- Historical role: release APK signing in neighboring Android projects
- MeteoOne RuStore role: `PENDING STORE-SIDE CONFIRMATION`

## Application-signing certificate

Purpose: long-lived Android application identity used to sign APKs installed by users. For RuStore-managed AAB delivery, RuStore signs generated APKs with the configured application-signing key.

- SHA-256 certificate fingerprint: `PENDING RUSTORE CONFIGURATION`
- Key algorithm / size: `PENDING`
- First RuStore release using this identity: `PENDING`
- Provisioning/configuration evidence: `PENDING`

If the existing external release certificate above is intentionally imported/configured as MeteoOne's RuStore application-signing identity, replace the pending values here with the same fingerprint and record the RuStore setup evidence. Otherwise record the distinct application-signing certificate selected in RuStore.

MeteoOne's release gate does not require a second functional installation test of the RuStore-generated APK after the paired canonical release APK has passed manual acceptance. Store-side signing configuration must still be correct because it establishes Android update identity.

## RuStore upload certificate

Purpose: upload identity used to sign AAB files submitted to RuStore. It is not necessarily the Android application-signing identity delivered to users when RuStore manages app signing separately.

Canonical `0.1.0-alpha.1` evidence:

- SHA-256 certificate fingerprint used by the canonical APK and AAB: `F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`
- Key algorithm / size: `RSA 2048`
- unified GitHub signed-release run: `35197092084`
- release source SHA: `e8035f648476669e9c839a8a510d02250faf4b18`
- Actions artifact id: `10486141848`
- canonical APK SHA-256: `eefac07c4b929e2886a6412c20e61a8141bd479b01a71a8fdcd0cfb9074e48f3`
- canonical AAB SHA-256: `604f1987183cdbcc68ac95041c522665c9005a9340934fbea6eba0063194cdac`
- APK build-provenance attestation: `48125618`
- AAB build-provenance attestation: `48125637`
- RuStore registration status for this certificate: `PENDING STORE-SIDE CONFIRMATION`
- First RuStore alpha using this upload key: `PENDING STORE ACCEPTANCE`

The real unified Actions run restored the repository-secret JKS, validated this fingerprint before Gradle signing, built the APK and AAB together, verified both binaries after build, generated common checksum/provenance evidence, attested both binaries, and removed the temporary keystore.

The earlier AAB evidence from run `35109403734` / SHA-256 `b5da2069b7d08d7eec39882aef921702c145d8648fe082ce6c6d0019b7bcd93c` remains historical provenance only and is superseded by the canonical unified AAB above.

A public PEM upload certificate can be exported directly from the canonical signed AAB with `scripts/export_aab_upload_certificate.py`; doing so does not expose the private key. RuStore still requires separate application-signing setup for AAB delivery according to its current signing flow.

Example:

```text
python3 scripts/export_aab_upload_certificate.py \
  meteoone-0.1.0-alpha.1.aab \
  meteoone-0.1.0-alpha.1-uploadcert.pem \
  --expected-sha256 F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58
```

## Recording procedure

Before the first store upload:

1. decide which key establishes long-lived MeteoOne **application signing**;
2. confirm in RuStore Console which certificate is registered as the **AAB upload key**;
3. compare the RuStore upload certificate against the canonical AAB fingerprint above;
4. configure/import the intended application-signing key using the store's current AAB signing flow;
5. keep the JKS/password/alias values only in the GitHub Actions repository secrets described in [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md) when that JKS is intentionally the AAB upload key;
6. do not attach or commit private signing material, passwords or PEPK output;
7. update the remaining pending public metadata here through a reviewed pull request after RuStore configuration/acceptance provides the relevant public evidence.

Treat terminal history, generated PEPK ZIP files, and temporary keystore copies as sensitive material. Follow [SIGNING.md](SIGNING.md), [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md), and [RUSTORE_ALPHA.md](RUSTORE_ALPHA.md).
