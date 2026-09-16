# MeteoOne release certificate fingerprints

Status: **SIGNED AAB CERTIFICATE VERIFIED — RuStore role assignment still requires store-side confirmation.**

This file is a public integrity record only. Never commit a keystore, private key, password, PEPK export, secret or recovery material here or elsewhere in the repository.

An existing JKS was inspected locally on 2026-09-16 and then exercised by the real GitHub signed-release workflow. It contains one private-key entry with alias `key0` and certificate SHA-256:

`F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`

The same public certificate fingerprint is already used by neighboring StanleyLl0yd Android projects for release APK signing. That establishes this certificate as an existing release/application-signing identity in the owner's Android project set; it does **not** by itself prove that RuStore has registered it as MeteoOne's AAB upload certificate or application-signing certificate.

## Existing external release certificate

- Alias observed locally / used by Actions: `key0`
- SHA-256 certificate fingerprint: `F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`
- Private-key entry count observed: `1`
- Key algorithm / size: `RSA 2048`
- Subject observed by `apksigner`: `C=RU, ST=Saint-Petersburg, L=Saint-Petersburg, O=Silver Lightning, OU=SL, CN=Stanley Lloyd`
- Historical role: release APK signing in neighboring Android projects
- MeteoOne RuStore role: `PENDING STORE-SIDE CONFIRMATION`

## Application-signing certificate

Purpose: long-lived Android application identity used to sign APKs installed by users. For RuStore-managed AAB delivery, RuStore signs the generated APKs with the configured application-signing key.

- SHA-256 certificate fingerprint: `PENDING RUSTORE CONFIGURATION`
- Key algorithm / size: `PENDING`
- First RuStore-delivered release using this identity: `PENDING`
- Provisioning evidence reviewed in: `PENDING`

If the existing external release certificate above is intentionally imported/configured as MeteoOne's RuStore application-signing identity, replace the pending values here with the same fingerprint and record the RuStore setup evidence. After the first store installation, independently verify the certificate on the RuStore-delivered APK rather than assuming it from the upload AAB.

## RuStore upload certificate

Purpose: upload identity used to sign AAB files submitted to RuStore. It is not necessarily the Android application-signing identity delivered to users when RuStore manages app signing separately.

- SHA-256 certificate fingerprint used by the verified GitHub AAB: `F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`
- Key algorithm / size: `RSA 2048`
- GitHub signed-build run: `35109403734`
- Source SHA: `ae27269aa753ebcd812922b5023d5f6854810df9`
- AAB SHA-256: `b5da2069b7d08d7eec39882aef921702c145d8648fe082ce6c6d0019b7bcd93c`
- GitHub build-provenance attestation: `47936791`
- RuStore registration status for this certificate: `PENDING STORE-SIDE CONFIRMATION`
- First RuStore alpha using this upload key: `PENDING STORE ACCEPTANCE`

The real Actions run restored the repository-secret JKS, validated this fingerprint before Gradle signing, verified the signed APK and AAB after build, and removed the temporary keystore. The downloaded AAB was then independently checked again and contains exactly one certificate with the same SHA-256 fingerprint.

A public PEM upload certificate can be exported directly from this signed AAB with `scripts/export_aab_upload_certificate.py`; doing so does not expose the private key. RuStore still requires separate application-signing setup for AAB delivery according to its current signing flow.

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
3. compare the RuStore upload certificate against the verified AAB fingerprint above;
4. configure/import the intended application-signing key using the store's current AAB signing flow;
5. keep the JKS/password/alias values only in the GitHub Actions repository secrets described in [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md) when that JKS is intentionally the AAB upload key;
6. do not attach or commit private signing material, passwords or PEPK output;
7. after RuStore generates/install-delivers the APK, verify and record the delivered APK application-signing fingerprint;
8. update the remaining pending public metadata here through a reviewed pull request.

Treat terminal history, generated PEPK ZIP files, and temporary keystore copies as sensitive material. Follow [SIGNING.md](SIGNING.md), [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md), and [RUSTORE_ALPHA.md](RUSTORE_ALPHA.md).
