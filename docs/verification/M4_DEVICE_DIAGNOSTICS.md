# M4 device diagnostics: evidence gate #303

This is an explicit, opt-in maintenance diagnostic. It leaves forecast algorithms,
provider selection and storage schemas unchanged. No MeteoOne server or telemetry exists.

## On-device procedure

1. Install a future signed numbered maintenance APK from reviewed source. Verify its
   checksum and signature. **Release 0.4.0 is immutable and does not contain this UI.**
2. On a physical `arm64-v8a` Android device, select a forecast location.
3. In Settings, tap "Run one diagnostic refresh". This triggers one forecast refresh.
   No diagnostic collection runs when this feature is not explicitly invoked.
4. Inspect the aggregate report. If safe, tap "Copy sanitized report" and attach it
   to #303, preserving version, ABI and UTC timestamp. The clipboard can be accessible
   to other applications depending on Android platform behavior.
5. Never attach raw logs, coordinates, station identities, Room databases,
   HTTP URLs, request payloads, or screenshots exposing the forecast grid.
6. Clear the in-app report and clipboard. Process restart loses the report;
   no diagnostic report is persisted or uploaded.

## Evidence interpretation

- Each source row is the actual attempted provider/model pair. Open-Meteo fallback
  never counts as official GRIB. A successful provider label alone never proves JNI.
- `nativeDecodeSuccesses > 0` records successful completion of bundled ecCodes/JNI
  plus usable field semantic binding. `officialGrib=true` additionally requires
  the official provider attempt to succeed.
- Exact-run attempted indicates an actual M4 acquisition attempt, not successful
  retrieval. Stored run count is model/run entries, not distinct timestamps.
- GHCNh observations count the selected stored or newly archived station series
  during this refresh, without exposing station identity. Zero is not proof of
  a specific failure mode.
- Sample counts follow QC and matching, grouped by parameter.
- `measured` requires a successful M4 weight policy decision; equal fallback
  provides policy reason categories. No confidence probability is claimed.
- A failed/cancelled refresh cannot satisfy the acceptance gate.

Physical-device execution evidence is mandatory to close #303. Host JNI
regressions, signed APK integrity and CI remain separate, insufficient evidence.
