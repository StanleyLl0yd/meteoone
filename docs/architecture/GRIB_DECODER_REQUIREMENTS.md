# GRIB decoder capability gate

Status: M1 measured-evidence, Android acceptance and production-integration gates satisfied. **Selected and integrated M1 production decoder path: ecCodes 2.48.0 + libaec 1.1.4.**

This document records the minimum GRIB2 capability envelope measured from MeteoOne's official-source probes, the Android acceptance evidence used to select the production decoder path, and the production boundary that now implements it. Issue #26 is the authority for the immutable measured-source corpus. Issue #82 is the decoder-candidate selection authority.

## Measured minimum envelope

The immutable #26 corpus establishes the current lower bound:

| Provider | GDT | PDT | DRT | Transport boundary |
| --- | --- | --- | --- | --- |
| NOAA NOMADS | `0` | `0, 8` | `0` | GRIB2; concatenated messages occur in the measured corpus |
| ECMWF Open Data | `0` | `0, 8` | `42` | exact HTTP 206 ranges selected from a retained `.index` |
| DWD Open Data | `101` | `0, 8` | `42` | outer bzip2; decompression remains outside the GRIB decoder |

Canonical immutable corpus evidence:

- workflow run `34577654571`;
- source SHA `cc19d5d8fafa64c32a652fc0360ff56ddcfe967d`;
- artifact ID `10190349074`;
- artifact digest `sha256:5556b372bbf5f6b79ed3299597c2a92ae7ac6a0519b3916008972fcfc53fa5c7`;
- model run `2026-09-10T18:00:00Z`, forecast hour `6`;
- 28 measured samples;
- retained ECMWF index SHA-256 `2514ac28a8d00a725262dca79dc5db3ab6185a9ed1a273a6b147d0c1dd01e368`.

Any future decoder change must preserve value-decode support for GDT `{0,101}`, PDT `{0,8}`, DRT `{0,42}`, NOAA concatenated messages, and DWD input after bounded outer-bzip2 decompression.

## Candidate decision

Exact research source pins are recorded in `research/grib_decoder_candidates/pins.json`.

| Candidate | M1 status | Reason |
| --- | --- | --- |
| ECMWF ecCodes 2.48.0 + libaec 1.1.4 | **Selected and integrated** | Passed the exact immutable corpus, API 26 `arm64-v8a` build/package gate, minimum-Android runtime execution, real Android 15 16 KiB runtime execution, malformed-input rejection, licence review and resource/binary evidence. Direct C API and permissive Apache-2.0/BSD-2-Clause licensing keep the production boundary narrow. |
| netCDF-Java 5.10.0 | **Blocked fallback** | DRT 42 uses JNA plus native libaec. Upstream native binaries do not provide an Android path, so adoption would require a custom Android libaec package plus JNA/API26/16 KiB proof. |
| NOAA wgrib2 3.8.0 | **Not advanced in M1** | The exact DRT 42 path requires NCEPLIBS-g2c 2.3.0; the stock library surface also carries broader native and licence/maintenance obligations, including GPL-3.0-or-later source. A source-pruned fork would be a distinct future candidate. |

The wgrib2 decision is an engineering distribution/maintenance decision, not a claim that GPL/LGPL software cannot be distributed on Android.

## Accepted ecCodes Android evidence

The authoritative selection run is:

- workflow: `GRIB Decoder Candidate Evaluation`;
- run ID `34815805413` / run #4;
- event `workflow_dispatch`;
- branch `main`;
- source SHA `64a595054200832c8d85dfc3d293eb0fa0f27c92`;
- conclusion `success`;
- artifact ID `10336329301`;
- artifact name `meteoone-m1-grib-decoder-eval-34815805413`;
- artifact digest `sha256:8a8c0e427284c187e16aedac17deb33be1f4fe40637b111fd262554b7fa98497`.

The run proved all of the following against the immutable #26 corpus:

1. Host value-decode of the representative GDT/PDT/DRT envelope and measured concatenated NOAA input.
2. Controlled failure on the deterministic truncated DRT 42 fixture.
3. API 26 `arm64-v8a` cross-build with NDK `28.2.13676358`.
4. All produced arm64 shared objects report AArch64 and every PT_LOAD has `p_align = 0x4000`.
5. Package-shaped `lib/arm64-v8a/*.so` archive passes `zipalign -P 16` verification.
6. API 26 x86_64 Android runtime value-decodes the same envelope and concatenated NOAA input; malformed DRT 42 exits with controlled status `1`.
7. Android 15/API 35 `google_apis_ps16k` x86_64 runtime reports `PAGE_SIZE=16384`, value-decodes the same envelope and concatenated NOAA input, and rejects malformed DRT 42 with controlled status `1`.
8. No staged runtime binary requires an unstaged `libc++_shared.so`.

The runtime ABI split is deliberate: x86_64 emulator execution proves Android runtime behavior; it does not replace the mandatory `arm64-v8a` production-ABI build/package evidence.

## Resource and binary impact

Accepted Android arm64 installed shared-library sizes from run `34815805413`:

- `libeccodes.so`: `37,902,824` bytes;
- `libaec.so`: `122,032` bytes;
- `libsz.so`: `137,656` bytes.

The package-shaped archive contains only those three arm64 shared libraries and reports `38,162,512` uncompressed bytes for its five archive entries including directories. Its SHA-256 is `31f9a5a6a7e55f210dcb5e1a623207e57a3573a889c58323c3b66a4ec84ff496`.

Representative Android runtime peak RSS remained bounded around the measured full-field envelope: approximately 45-46 MiB for the largest DWD/ECMWF representatives, while the measured concatenated NOAA point sample remained around 14-15 MiB. These measurements are research evidence for the current exact build, not a promise of final APK/AAB download size or final production memory use.

The production path continues to avoid retaining or decoding an unbounded multi-hour × multi-field matrix. #89 established coordinate-aware point selection and the native/grid ownership boundary.

## Production native acceptance contract

The selected decoder does not waive future native-code requirements. The current production integration and any future decoder/version change must preserve:

1. minimum Android API 26 support;
2. mandatory `arm64-v8a` output;
3. 16 KiB-compatible ELF segments and native-library packaging;
4. actual 16 KiB-page runtime verification for material native/toolchain changes;
5. bounded input, allocation and decoded-value limits;
6. fail-closed malformed/truncated handling;
7. full third-party/native/grid representations contained inside `:forecast:data`;
8. MeteoOne-owned types only across `:forecast:data` -> domain/model boundaries;
9. direct/transitive licence and notice review for version changes;
10. reproducible source/version pins for native builds.

## Production integration and #89 acceptance evidence

Production integration began only after issue #82 was completed on `2026-09-14T07:16:01Z`. PR #108, which integrated the selected ecCodes JNI path, was created later at `2026-09-14T10:53:36Z` and merged as `a8c5105a1fbfdd2b4047aec17f8fe4113da28d71`.

The spatial and ownership boundary is deliberately contained inside `:forecast:data`:

- JNI/ecCodes carrier types (`NativeGribMessage`, `EcCodesNativeApi`, native bridge/session) are `internal` to the module;
- full ECMWF GDT 0 geometry and DWD CLAT/CLON arrays are represented only by `internal` `:forecast:data` types;
- decoded native metadata/geometry/value arrays are validated, reduced to one selected grid value and translated into `DecodedGribField` before mapping to the canonical `SourceForecast` model;
- `OfficialGribDecodeContext` accepts only validated provider plans plus `ForecastCoordinate`; raw Android `Location` values are normalized in `:core:location` before they can enter forecast planning;
- `:forecast:domain` depends only on `:core:model`; the Android app reaches the production M1 composition through `:forecast:data`, while native/ecCodes implementation types remain internal to that module.

Issue #89 point-selection regressions establish the provider-specific behavior:

- NOAA GFS requires one GDT 0 point whose decoded coordinates match the snapped request-plan grid point, and rejects contradictory coordinates/cardinality;
- ECMWF IFS requires the supported regular GDT 0 geometry, exact value cardinality, deterministic nearest-cell selection including longitude wrap and half-cell ties, and rejects unsupported scan geometry or non-finite selected values;
- DWD ICON requires GDT 101, exact field/CLAT/CLON cardinality, geometry from the same validated model run, deterministic nearest-spherical selection with stable lowest-index ties, and finite coordinates/selected values;
- semantic binding independently validates GRIB edition, discipline, parameter, PDT/GDT/DRT, surface, model run, valid time/statistical interval and canonical unit transform against the provider plan;
- `OfficialGribForecastMapper` directly consumes the resulting point-level `DecodedGribField` values and enforces forecast-level invariants.

DWD geometry lifecycle is process-memory only and run scoped. PR #109 merged as `205553e3972bdd7769a96726960c0cf7928cc9d4`; same-run geometry is reused, a different run reloads CLAT/CLON, and replacement is atomic so a failed reload cannot publish partial geometry. This remains an M1 transient execution optimization, not a persistent cache.

The canonical production-JNI regression on the post-#110 `main` commit proves the real production C/JNI bridge against the immutable provider corpus:

- source SHA `0556fe03a412755e5a5f4ec0af9db9c986cbc76f`;
- workflow `GRIB Production JNI Corpus Regression`, run `34838775717`;
- job `103958628889`, conclusion `success`;
- artifact `10343989925`, `meteoone-m1-production-jni-regression-34838775717`;
- artifact digest `sha256:f7395e9328c6b9964b1e90680bff7b3a9e942a7973934e62c02d2bcc85fa8e9f`;
- NOAA temperature: 1 message / 1 value;
- NOAA precipitation: 2 messages / 2 values;
- DWD temperature: 1 message / 2,949,120 values;
- DWD precipitation: 1 message / 2,949,120 values;
- ECMWF temperature: 1 message / 1,038,240 values;
- ECMWF precipitation: 1 message / 1,038,240 values;
- total decoded values: `7,974,723`.

The same regression fails closed for message-count bounds, decoded-value bounds, truncated payloads, trailing bytes, non-GRIB payloads, zero message limits and zero value limits. The post-merge commit also has successful CI (`34838775701`), Security and Quality (`34838775699`) and Secret Scan (`34838775645`) runs; CodeQL (`34838775670`) was skipped by the repository's compatibility gate. The post-M3 audit re-probed compiled Kotlin CodeQL and confirmed that stable CLI 2.27.0 still rejects Kotlin 2.4.20, so that gate remains required.

These production results supplement rather than replace the accepted Android API 26/16 KiB runtime evidence above. Together with `GribPointSelectionTest`, `GribSemanticBindingTest`, `OfficialGribForecastMapperInvariantTest`, `EcCodesGribFieldDecoderTest` and `RunScopedDwdIconGridGeometryProviderTest`, they satisfy the #89 decoder/selection boundary acceptance envelope.

## Malformed-input and fuzz/sanitizer strategy

The deterministic truncated DRT 42 case is an established regression gate. Additional native hardening should:

- add a host Clang ASan+UBSan build of the narrow decode entry point;
- seed mutation/fuzz runs with the exact #26 representatives, including concatenated NOAA and decompressed DWD samples;
- cover truncation, section-length corruption, duplicated/reordered sections, invalid template identifiers, bitmap/value-count inconsistencies and random byte mutations;
- continue validating Kotlin/JNI lengths and ownership before native calls;
- continue converting decoder failures into bounded provider errors rather than process termination;
- preserve minimized crashing cases as regression fixtures when redistribution permits.

Longer fuzz campaigns may remain outside normal PR CI when their duration/resource use is unsuitable for the standard gate.

## Current milestone state

Issue #82 candidate selection, the selected ecCodes production integration, #89 coordinate-aware selection and native/grid containment, run-scoped DWD geometry, the production-JNI corpus regression, and the top-level M1 forecast execution/orchestration are all present in the completed M1 codebase.

This document remains the decoder acceptance contract for future native/toolchain changes. M2 persistence/cache/repository and M3 product-UI work are now completed layers above this M1 decoder boundary; those later responsibilities do not alter the decoder ownership or acceptance contract.

## Upstream references

- netCDF-Java releases: <https://github.com/Unidata/netcdf-java/releases>
- netCDF-Java licence: <https://github.com/Unidata/netcdf-java>
- ecCodes releases: <https://github.com/ecmwf/eccodes/releases>
- ecCodes repository/licence: <https://github.com/ecmwf/eccodes>
- wgrib2 releases: <https://github.com/NOAA-EMC/wgrib2/releases>
- wgrib2 documentation: <https://noaa-emc.github.io/wgrib2/>
- NCEPLIBS-g2c: <https://github.com/NOAA-EMC/NCEPLIBS-g2c>
- libaec: <https://github.com/Deutsches-Klimarechenzentrum/libaec>
- Android 16 KiB page-size guidance: <https://developer.android.com/guide/practices/page-sizes>
- Android NDK r28 changelog: <https://github.com/android/ndk/wiki/Changelog-r28>
