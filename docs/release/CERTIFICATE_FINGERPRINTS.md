# MeteoOne release certificate fingerprints

Status: **EXTERNAL RELEASE CERTIFICATE VERIFIED LOCALLY — RuStore role assignment still requires store-side confirmation.**

This file is a public integrity record only. Never commit a keystore, private key, password, PEPK export, PEM certificate, secret, or recovery material here or elsewhere in the repository.

An existing JKS was inspected locally on 2026-09-16. It contains one private-key entry with alias `key0` and certificate SHA-256:

`F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`

The same public certificate fingerprint is already used by neighboring StanleyLl0yd Android projects for release APK signing. That establishes this certificate as an existing release/application-signing identity in the owner's Android project set; it does **not** by itself prove that RuStore has registered it as MeteoOne's AAB upload certificate.

## Existing external release certificate

- Alias observed locally: `key0`
- SHA-256 certificate fingerprint: `F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`
- Private-key entry count observed: `1`
- Key algorithm / size: `PENDING`
- Historical role: release APK signing in neighboring Android projects
- MeteoOne RuStore role: `PENDING STORE-SIDE CONFIRMATION`

## Application-signing certificate

Purpose: long-lived Android application identity used to sign APKs installed by users. The first RuStore release establishes this identity and future stores must preserve it where cross-store update compatibility is required.

- SHA-256 certificate fingerprint: `PENDING RUSTORE CONFIGURATION`
- Key algorithm / size: `PENDING`
- First release using this identity: `PENDING`
- Provisioning evidence reviewed in: `PENDING`

If the existing external release certificate above is intentionally imported/configured as MeteoOne's RuStore application-signing identity, replace the pending value here with the same fingerprint and record the RuStore setup evidence.

## RuStore upload certificate

Purpose: upload identity used to sign AAB files submitted to RuStore. It is **not** the Android application-signing identity delivered to users when RuStore manages app signing separately.

- SHA-256 certificate fingerprint currently stored for the GitHub signed-build check: `F0:25:71:C4:07:41:E2:CB:07:15:64:F5:B6:3F:D3:DC:38:A8:75:D0:ED:A1:1A:8C:42:26:9E:D6:35:BC:2A:58`
- RuStore registration status for this certificate: `PENDING STORE-SIDE CONFIRMATION`
- Key algorithm / size: `PENDING`
- First release using this upload key: `PENDING`

The GitHub secret value documents what certificate the current manual signed-build workflow expects; it does not substitute for checking which upload certificate RuStore Console actually registers for MeteoOne.

## Recording procedure

Before the first store upload:

1. decide which key establishes long-lived MeteoOne **application signing**;
2. confirm in RuStore Console which certificate is registered as the **AAB upload key**;
3. derive each public certificate fingerprint locally from the corresponding keystore/certificate;
4. verify the application-signing fingerprint independently before producing/configuring any RuStore protected key import;
5. verify the RuStore upload-certificate fingerprint from the same key that signs the AAB;
6. keep the JKS/password/alias values only in the GitHub Actions repository secrets described in [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md) when that JKS is intentionally the AAB upload key;
7. set `ANDROID_UPLOAD_CERT_SHA256` to the independently derived certificate fingerprint;
8. run the signed-artifact workflow and require its pre-build and post-build fingerprint checks to match;
9. update the pending public metadata above through a reviewed pull request after RuStore role assignment is confirmed;
10. do not attach or commit certificate/private material itself;
11. compare these recorded fingerprints against the RuStore signing/upload configuration again immediately before the first manual store upload.

Example local inspection commands (paths/aliases are intentionally placeholders and passwords should be entered interactively):

```text
keytool -list -v -keystore /secure/path/application-signing.jks -alias application-signing
keytool -list -v -keystore /secure/path/rustore-upload.jks -alias rustore-upload
```

Treat terminal history, generated PEPK ZIP files, and temporary keystore copies as sensitive material. Follow [SIGNING.md](SIGNING.md), [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md), and [RUSTORE_ALPHA.md](RUSTORE_ALPHA.md).
