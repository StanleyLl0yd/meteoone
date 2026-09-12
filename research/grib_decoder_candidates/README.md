# M1 GRIB decoder candidate evaluation

This directory is research-only evidence for issue #82. It does not select or add a production decoder dependency.

## Immutable corpus

Candidate behavior must be evaluated only against the successful issue #26 capability artifact pinned in `pins.json`:

- workflow run `34577654571`;
- source SHA `cc19d5d8fafa64c32a652fc0360ff56ddcfe967d`;
- artifact ID `10190349074`;
- artifact digest `sha256:5556b372bbf5f6b79ed3299597c2a92ae7ac6a0519b3916008972fcfc53fa5c7`;
- model run `2026-09-10T18:00:00Z`, forecast hour `6`;
- 28 measured samples;
- retained ECMWF index SHA-256 `2514ac28a8d00a725262dca79dc5db3ab6185a9ed1a273a6b147d0c1dd01e368`.

`verify_corpus.py` verifies the artifact manifest, exact file set, every file checksum, schema/run/hour/sample count, the measured provider template envelope, and the retained ECMWF index before preparing any candidate input.

Measured decoder envelope:

| Provider | GDT | PDT | DRT | Transport boundary |
| --- | --- | --- | --- | --- |
| NOAA NOMADS | `0` | `0, 8` | `0` | GRIB2; concatenated messages occur in the measured corpus |
| ECMWF Open Data | `0` | `0, 8` | `42` | exact HTTP 206 ranges retained by #26 |
| DWD Open Data | `101` | `0, 8` | `42` | outer bzip2; decompression remains outside the GRIB decoder |

The prepared six-sample smoke set covers temperature and precipitation for NOAA, ECMWF, and DWD. DWD `.bz2` files are decompressed only by the research preparation boundary, then re-inspected with MeteoOne's bounded GRIB2 inspector before a decoder sees them. The manual evaluation also locates a measured concatenated NOAA sample and requires the candidate to value-decode every message in that file.

## Candidate state

All upstream source pins are immutable commit SHAs in `pins.json`.

### ecCodes 2.48.0 + libaec 1.1.4

Current priority: **leading native research candidate; not selected for production**.

Reasons:

- direct C API;
- GDT/PDT handling covers the measured envelope;
- DRT 42 is provided through libaec;
- no JNA/resource-extraction layer is required;
- ecCodes is Apache-2.0 and libaec is BSD-2-Clause.

The ecCodes build is supplied the exact ecbuild 3.12.0 checkout externally. Evaluation must not allow ecCodes' fallback `FetchContent` path to resolve the mutable `3.12.0` tag.

The first immutable-corpus workflow run produced successful host value-decode and malformed-input evidence, but did not complete the Android cross-build. The observed Android configure blocker was fixed by #84. PR #87 subsequently added research-only API 26 and Android 15 16 KiB runtime harnesses while preserving the independent API 26 `arm64-v8a` build/ELF/package gate. A fresh workflow dispatch from the latest `main` is still required before those Android gates can be accepted as evidence.

### netCDF-Java 5.10.0

Current state: **Android native path blocked/unverified; decoding capability not rejected**.

The project is BSD-3-Clause. DRT 42 is not a pure-Java path: upstream `Grib2DataReader` uses JNA plus native libaec. At the exact pinned source, `LibAec` first calls `Native.extractFromResourcePath("aec")` and falls back to `Native.register("aec")` on the system library path. Upstream's own 5.10.0 documentation publishes `libaec-native` only for Linux, macOS, and Windows on x86-64/aarch64; Android is not in that native-binary matrix.

An Android adoption would therefore require a custom Android libaec package plus proof that the JNA loading path works on API 26 and `arm64-v8a`, in addition to the same 16 KiB ELF/package/runtime gates. This makes netCDF-Java a blocked fallback candidate for M1 rather than a ready pure-Java alternative.

### wgrib2 3.8.0

Current state: **not advancing the stock 3.8.0 library target to Android smoke in M1**.

The exact pinned `v3.8.0` source is commit `986287cc4f77ed3f5f97056fd0f90d099964dba7`. Exact-source review corrects the earlier provisional assumption that NCEPLIBS-g2c was unnecessary for MeteoOne's DRT 42 decode path:

- `unpk.c` handles DRT 42 only under `USE_G2CLIB_LOW` and calls `g2c_dec_aec`; without that build mode it reports AEC decoding as unsupported;
- the v3.8.0 release notes independently state that AEC compression moved to NCEPLIBS-g2c 2.3.0;
- the exact g2c 2.3.0 tag is commit `acbccb8a894255cd30056b722781a7c9f7b1fe3c` and its `LICENSE.md` is LGPL-3.0;
- the stock `wgrib2_lib` target is built from the common source list and does not exclude `aec_pk.c`;
- exact pinned `aec_pk.c` is explicitly GPL-3.0-or-later, even though MeteoOne needs AEC decoding rather than that file's AEC packing path;
- the stock target also links the bundled GCTPC library unconditionally and its default build surface enables additional facilities that MeteoOne does not require unless explicitly disabled.

The repository-level README contains a U.S. Department of Commerce/public-domain disclaimer for government-authored portions, but it also explicitly preserves third-party licence obligations. It therefore cannot be treated as a single permissive licence grant over every object in the stock library.

This is an engineering distribution and maintenance decision, not a claim that GPL/LGPL software cannot be distributed on Android. MeteoOne could theoretically maintain a separately reviewed source-pruned fork that excludes unneeded GPL translation units and narrows the build graph, while still satisfying the licences of retained dependencies. That would no longer be the stock wgrib2 candidate evaluated here, would create a permanent fork/compliance burden, and offers no demonstrated advantage over the already narrower ecCodes + libaec path. M1 therefore does not spend an NDK/emulator evidence cycle on stock wgrib2 3.8.0.

## Observed ecCodes evidence

The first manual candidate run is useful **partial host evidence only**:

- workflow run `34618844204`;
- source SHA `4f30be720e8f2336fd22db3132e5ee9f7ceebddc`;
- evidence artifact ID `10271995909`;
- artifact digest `sha256:60ede567e8a4d7d627166099a0636ac91a83de773cadbdeb13a6aa8ccc240ecd`.

The run verified the exact #26 corpus, built the pinned host ecCodes/libaec stack, value-decoded the representative envelope and measured concatenated NOAA input, and rejected a truncated DRT 42 message deterministically. Representative measurements from that artifact are:

| Representative | Templates | Decoded values | CPU seconds | Max RSS KiB |
| --- | --- | ---: | ---: | ---: |
| DWD precipitation | GDT 101 / PDT 8 / DRT 42 | 2,949,120 | 0.046130 | 45,404 |
| DWD temperature | GDT 101 / PDT 0 / DRT 42 | 2,949,120 | 0.035855 | 46,328 |
| ECMWF precipitation | GDT 0 / PDT 8 / DRT 42 | 1,038,240 | 0.017500 | 46,328 |
| ECMWF temperature | GDT 0 / PDT 0 / DRT 42 | 1,038,240 | 0.010302 | 46,328 |
| NOAA concatenated cloud cover | GDT 0 / PDT 0+8 / DRT 0 | 2 messages / 2 values | 0.017448 | 14,776 |

The same smoke also decoded the single-message NOAA temperature and precipitation representatives. The truncated DRT 42 case exited non-zero with `End of resource reached when reading message`; it did not silently succeed.

Host installed native sizes captured by the run were:

- `libeccodes.so`: `3,706,776` bytes;
- `libaec.so.0.1.4`: `47,456` bytes;
- `libsz.so.2.0.1`: `51,888` bytes.

These are host-build measurements and are **not** Android APK/AAB size claims.

The run then failed while configuring ecCodes for Android because pinned ecbuild rejected the unrecognised `Android` operating system. PR #84 added `DISABLE_OS_CHECK=ON` and pre-seeded the known little-endian arm64 target values. PR #87 added the remaining runtime harness. Neither change has yet been exercised by a fresh manual run on the current `main`, so the failed run must not be cited as Android-build or current-head acceptance evidence. Re-running the old Actions run is also insufficient because it remains tied to its old source SHA; the next accepted evaluation must be a new `workflow_dispatch` from the latest `main`.

## Provisional recommendation

Based on the evidence available so far, **ecCodes + libaec remains the only candidate being advanced through the Android gates**. It already value-decodes the complete measured template envelope on the host, rejects the deterministic malformed fixture, has a narrow direct C API, and has clear permissive licences for the two required libraries.

This is deliberately not a production selection. Issue #82 remains blocked on a fresh latest-`main` Android cross-build/package/API26/16 KiB runtime evaluation and the resulting Android resource/binary-size evidence. netCDF-Java remains a blocked fallback pending a custom Android libaec/JNA path. Stock wgrib2 3.8.0 is not advanced in M1 because exact-source review shows its DRT42 path requires LGPL-3.0 g2c and its standard library target also incorporates GPL-3.0-or-later source plus a broader native surface; a custom-pruned fork would require a separate architecture, maintenance, and compliance decision.

## ecCodes smoke contract

`eccodes_smoke.c` deliberately exercises value decoding rather than metadata-only parsing. For every GRIB message it:

1. creates the handle with `codes_handle_new_from_file`;
2. reads GDT, PDT, and DRT keys;
3. obtains the `values` size;
4. calls `codes_get_double_array("values", ...)` to force real field decode, including DRT 42;
5. reports message/value counts, min/max, CPU time, and Linux maximum RSS;
6. fails on decoder errors, zero messages, zero values, allocation failure, or no finite decoded values.

The manual workflow runs this over all six representatives and over a measured concatenated NOAA file. It then truncates a representative DRT 42 GRIB and requires the same smoke binary to fail.

## Android/native acceptance gates

A native candidate is not production-eligible until all applicable gates are satisfied:

- Android API 26 cross-build;
- mandatory `arm64-v8a` output;
- exact NDK `28.2.13676358` for this evaluation;
- every produced ELF shared object reports AArch64 and every `PT_LOAD` has `p_align >= 0x4000`;
- `DT_NEEDED` and installed native byte sizes are captured;
- a package-shaped archive with `lib/arm64-v8a/*.so` passes `zipalign -P 16` verification;
- the measured corpus value-decodes successfully for GDT `{0,101}`, PDT `{0,8}`, and DRT `{0,42}`;
- malformed input fails safely;
- the same pinned decoder stack executes the representative decode contract on an API 26 Android runtime;
- actual execution on an Android 15 **16 KiB-page runtime** succeeds and records `PAGE_SIZE=16384`.

PR #87 implements the manual evidence harness using separate runtime purposes: the mandatory production ABI remains `arm64-v8a`; an API 26 x86_64 emulator proves minimum-platform execution; an API 35 `google_apis_ps16k` x86_64 emulator proves actual 16 KiB-page execution. x86_64 runtime success does not replace the arm64 build/ELF/package gate.

Cross-compilation and ELF/package inspection do **not** satisfy the final runtime gate. No production dependency may be added before fresh runtime evidence exists.

## Malformed-input and fuzz/sanitizer strategy

The deterministic first gate is a truncated immutable DRT 42 sample. The decoder must return failure; a crash or silent success is a rejection signal.

Before production adoption of any native decoder:

- add a host Clang ASan+UBSan build of the narrow decode entry point;
- seed mutation/fuzz runs with the exact #26 representatives, including the concatenated NOAA sample and decompressed DWD representatives;
- include truncation, section-length corruption, duplicated/reordered sections, invalid template identifiers, bitmap/value-count inconsistencies, and random byte mutations;
- enforce bounded input size and bounded allocation policy around the native boundary;
- preserve crashing/minimized cases as regression fixtures when redistribution permits;
- run the native fuzz target outside normal PR CI when duration/resource use is unsuitable for the standard gate.

A future JNI boundary must also validate Java/Kotlin lengths and ownership before native calls and must convert decoder failures into bounded provider errors rather than process termination.

## Evidence produced by the manual workflow

`.github/workflows/grib-decoder-candidate-eval.yml` is `workflow_dispatch`-only. It records immutable corpus/run metadata, exact upstream source SHAs, host build logs, value-decode output, malformed-input result, Android `arm64-v8a` build logs, ELF headers/program headers/dynamic dependencies, native sizes, and 16 KiB package-alignment verification. It also builds a pinned API 26 x86_64 runtime bundle, executes the decode/malformed contract on API 26, and repeats it on an Android 15 `google_apis_ps16k` emulator after requiring `PAGE_SIZE=16384`. Evidence upload runs under `if: always()` so a failed candidate build or runtime remains inspectable.
