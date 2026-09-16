# MeteoOne release certificate fingerprints

Status: **PENDING — no production signing keys have been provisioned yet.**

This file is a public integrity record only. Never commit a keystore, private key, password, PEPK export, PEM certificate, secret, or recovery material here or elsewhere in the repository.

## Application-signing certificate

Purpose: long-lived Android application identity used to sign APKs installed by users. The first RuStore release establishes this identity and future stores must preserve it where cross-store update compatibility is required.

- SHA-256 certificate fingerprint: `PENDING`
- Key algorithm / size: `PENDING`
- First release using this identity: `PENDING`
- Provisioning evidence reviewed in: `PENDING`

## RuStore upload certificate

Purpose: separate upload identity used to sign AAB files submitted to RuStore. It is **not** the Android application-signing identity delivered to users.

- SHA-256 certificate fingerprint: `PENDING`
- Key algorithm / size: `PENDING`
- First release using this upload key: `PENDING`
- Provisioning evidence reviewed in: `PENDING`

## Recording procedure

After keys are generated outside Git and securely backed up:

1. derive each public certificate fingerprint locally from the corresponding keystore/certificate;
2. verify the application-signing fingerprint independently before producing the RuStore PEPK export;
3. verify the RuStore upload-certificate fingerprint from the same upload key that will sign the AAB;
4. replace only the `PENDING` public metadata above in a reviewed pull request;
5. do not attach or commit the certificate/private material itself;
6. compare these recorded fingerprints against the signing/upload configuration again immediately before the first store upload.

Example local inspection commands (paths/aliases are intentionally placeholders):

```text
keytool -list -v -keystore /secure/path/application-signing.jks -alias application-signing
keytool -printcert -file /secure/path/rustore-upload-cert.pem
```

Treat terminal history, shell scripts containing passwords, generated PEPK ZIP files, and temporary keystore copies as sensitive material. Follow [SIGNING.md](SIGNING.md) and [RUSTORE_ALPHA.md](RUSTORE_ALPHA.md).
