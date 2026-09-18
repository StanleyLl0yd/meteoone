# RuStore alpha path — retired

This document is retained only to make the policy change explicit.

MeteoOne no longer publishes alpha or beta versions to RuStore.

Current release policy:

- every released version publishes signed APK + signed AAB in GitHub Releases;
- manual APK testing is optional for GitHub-only alpha/beta publication;
- alpha and beta stop at GitHub Releases;
- no certificate, PEM, checksum, mapping or provenance files are published as release assets;
- repository automation never uploads or publishes to RuStore;
- a future stable version selected for RuStore is handled under the M7 stable-store gate, using the AAB already published in its GitHub Release. Before any RuStore AAB upload, the APK from that same GitHub Release must pass manual device acceptance testing; if the APK fails, do not upload its AAB.

The former `0.1.0-alpha.1` RuStore closed-alpha plan was closed as not planned in issue #153 on 2026-09-17.
