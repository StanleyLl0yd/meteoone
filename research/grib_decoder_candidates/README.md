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

The first immutable-corpus workflow run produced successful host value-decode and malformed-input evidence, but did not complete the Android cross-build. The observed Android configure blocker was fixed by #84; a fresh workflow dispatch from the latest `main` is still required before Android build evidence can be accepted.

### netCDF-Java 5.10.0

Current state: **Android native path blocked/unverified; decoding capability not rejected**.

The project is BSD-3-Clause. DRT 42 is not a pure-Java path: upstream `Grib2DataReader` uses JNA plus native libaec. At the exact pinned source, `LibAec` first calls `Native.extractFromResourcePath("aec")` and falls back to `Native.register("aec")` on the system library path. Upstream's own 5.10.0 documentation publishes `libaec-native` only for Linux, macOS, and Windows on x86-64/aarch64; Android is not in that native-binary matrix.

An Android adoption would therefore require a custom Android libaec package plus proof that the JNA loading path works on API 26 and `arm64-v8a`, in addition to the same 16 KiB ELF/package/runtime gates. This makes netCDF-Java a blocked fallback candidate for M1 rather than a ready pure-Java alternative.

### wgrib2 3.8.0

Current state: **licence composition review required before it can become a production candidate**.

For the exact pinned source and measured DRT set, AEC support is built around libaec. The pinned README describes libaec as the AEC dependency; NCEPLIBS-g2c is optional for JPEG/PNG paths selected by the corresponding CMake options and is not required for measured DRT `{0,42}`. Do not broaden the dependency surface from later release wording without verifying the exact pinned source.

The pinned tag has no conventional top-level `LICENSE` file. Its README carries a U.S. Department of Commerce disclaimer, while individual and third-party source components still need an explicit redistribution/composition review. Do not infer a single production licence from the README disclaimer alone.

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

The run then failed while configuring ecCodes for Android because pinned ecbuild rejected the unrecognised `Android` operating system. PR #84 added `DISABLE_OS_CHECK=ON` and pre-seeded the known little-endian arm64 target values. That fix has not yet been exercised by a fresh manual run on the current `main`, so the failed run must not be cited as Android-build or current-head acceptance evidence. Re-running the old Actions run is also insufficient because it remains tied to its old source SHA; the next accepted evaluation must be a new `workflow_dispatch` from the latest `main`.

## Provisional recommendation

Based on the evidence available so far, **ecCodes + libaec remains the first candidate to carry through the Android gates**. It already value-decodes the complete measured template envelope on the host, rejects the deterministic malformed fixture, has a narrow direct C API, and has clear permissive licences for the two required libraries.

This is deliberately not a production selection. Issue #82 remains blocked on fresh latest-`main` Android cross-build/package evidence and then actual execution on a 16 KiB-page Android runtime. netCDF-Java remains blocked on a custom Android libaec/JNA path, and wgrib2 remains blocked on licence/composition review before its larger native surface is worth advancing.

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
- actual execution on a **16 KiB-page Android runtime** succeeds.

Cross-compilation and ELF/package inspection do **not** satisfy the final runtime gate. No production dependency may be added before that runtime evidence exists.

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

`.github/workflows/grib-decoder-candidate-eval.yml` is `workflow_dispatch`-only. It records immutable corpus/run metadata, exact upstream source SHAs, host build logs, value-decode output, malformed-input result, Android build logs, ELF headers/program headers/dynamic dependencies, native sizes, and 16 KiB package-alignment verification. Evidence upload runs under `if: always()` so a failed candidate build remains inspectable.
