# Android release signing

## Policy

MeteoOne must keep a stable application identity and signing lineage across RuStore and Google Play.

The application signing key is never committed to Git and should be backed up securely outside the development machine.

## CI

Normal pull-request CI builds unsigned or debug artifacts and must not receive production signing material.

Production signing is performed only in an explicitly trusted release environment. Preferred order:

1. trusted local or self-hosted release runner with protected signing material;
2. protected GitHub Environment secrets only if operationally necessary.

## Artifacts

A production release should produce and verify:

- signed AAB;
- signed APK;
- SHA-256 checksums;
- R8 mapping file when minification is enabled;
- release notes.

## Google Play migration

When Google Play distribution becomes available, preserve the existing application identity and signing lineage. Use a distinct upload key when supported so routine uploads do not require exposure of the application signing key.
