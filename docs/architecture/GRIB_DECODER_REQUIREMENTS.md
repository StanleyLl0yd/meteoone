# GRIB decoder capability gate

Status: M1 measured-evidence and Android acceptance gates satisfied. **Selected M1 production decoder path: ecCodes 2.48.0 + libaec 1.1.4.**

This document records the minimum GRIB2 capability envelope measured from MeteoOne's official-source probes and the Android acceptance evidence used to select the production decoder path. Issue #26 is the authority for the immutable measured-source corpus. Issue #82 is the decoder-candidate selection authority.

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
| ECMWF ecCodes 2.48.0 + libaec 1.1.4 | **Selected** | Passed the exact immutable corpus, API 26 `arm64-v8a` build/package gate, minimum-Android runtime execution, real Android 15 16 KiB runtime execution, malformed-input rejection, licence review and resource/binary evidence. Direct C API and permissive Apache-2.0/BSD-2-Clause licensing keep the production boundary narrow. |
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

The production integration must continue to avoid retaining or decoding an unbounded multi-hour × multi-field matrix. #89 owns coordinate-aware point selection and the native/grid ownership boundary.

## Production native acceptance contract

The selected decoder does not waive future native-code requirements. Production integration and future decoder/version changes must preserve:

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

## Malformed-input and fuzz/sanitizer strategy

The deterministic truncated DRT 42 case is the first regression gate. Production native integration must additionally:

- add a host Clang ASan+UBSan build of the narrow decode entry point;
- seed mutation/fuzz runs with the exact #26 representatives, including concatenated NOAA and decompressed DWD samples;
- cover truncation, section-length corruption, duplicated/reordered sections, invalid template identifiers, bitmap/value-count inconsistencies and random byte mutations;
- validate Kotlin/JNI lengths and ownership before native calls;
- convert decoder failures into bounded provider errors rather than process termination;
- preserve minimized crashing cases as regression fixtures when redistribution permits.

Longer fuzz campaigns may remain outside normal PR CI when their duration/resource use is unsuitable for the standard gate.

## Integration sequencing

Issue #82 completes the candidate-selection phase. Production decoder integration must start only after #82 is closed. The next M1 slice is #89, which owns coordinate-aware official GRIB point selection and containment of native/full-grid representations inside `:forecast:data`.

M2/M3 remain out of scope until M1 Forecast Core is complete.

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
