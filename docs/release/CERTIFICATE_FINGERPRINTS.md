# MeteoOne release certificate fingerprints

Status: **EXTERNAL SIGNING KEY EXISTS — key role, public fingerprints, and GitHub release-secret registration still require verification.**

This file is a public integrity record only. Never commit a keystore, private key, password, PEPK export, PEM certificate, secret, or recovery material here or elsewhere in the repository.

The existence of a key outside Git does not by itself establish whether it is the long-lived application-signing key, the RuStore upload key, or both under an older/manual setup. Confirm the intended role before using it for the first MeteoOne store upload.

## Application-signing certificate

Purpose: long-lived Android application identity used to sign APKs installed by users. The first RuStore release establishes this identity and future stores must preserve it where cross-store update compatibility is required.

- SHA-256 certificate fingerprint: `PENDING`
- Key algorithm / size: `PENDING`
- First release using this identity: `PENDING`
- Provisioning evidence reviewed in: `PENDING`

## RuStore upload certificate

Purpose: upload identity used to sign AAB files submitted to RuStore. It is **not** the Android application-signing identity delivered to users when RuStore manages app signing separately.

- SHA-256 certificate fingerprint: `PENDING`
- Key algorithm / size: `PENDING`
- First release using this upload key: `PENDING`
- Provisioning evidence reviewed in: `PENDING`

## Recording procedure

Before the first signed GitHub build / store upload:

1. identify which existing key is the RuStore **upload** key and which key establishes long-lived **application signing**;
2. derive each public certificate fingerprint locally from the corresponding keystore/certificate;
3. verify the application-signing fingerprint independently before producing/configuring any RuStore protected key import;
4. verify the RuStore upload-certificate fingerprint from the same upload key that will sign the AAB;
5. store the upload-key JKS/password/alias values only in the protected GitHub `release` environment described in [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md);
6. set `ANDROID_UPLOAD_CERT_SHA256` to the independently derived upload fingerprint;
7. run the signed-artifact workflow and require its pre-build and post-build fingerprint checks to match;
8. replace only the `PENDING` public metadata above in a reviewed pull request;
9. do not attach or commit certificate/private material itself;
10. compare these recorded fingerprints against the RuStore signing/upload configuration again immediately before the first manual store upload.

Example local inspection commands (paths/aliases are intentionally placeholders and passwords should be entered interactively):

```text
keytool -list -v -keystore /secure/path/application-signing.jks -alias application-signing
keytool -list -v -keystore /secure/path/rustore-upload.jks -alias rustore-upload
```

Treat terminal history, generated PEPK ZIP files, and temporary keystore copies as sensitive material. Follow [SIGNING.md](SIGNING.md), [GITHUB_SIGNED_BUILD.md](GITHUB_SIGNED_BUILD.md), and [RUSTORE_ALPHA.md](RUSTORE_ALPHA.md).
