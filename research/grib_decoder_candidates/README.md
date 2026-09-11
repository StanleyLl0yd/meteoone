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

Current priority: **first native research smoke; not selected for production**.

Reasons:

- direct C API;
- GDT/PDT handling covers the measured envelope;
- DRT 42 is provided through libaec;
- no JNA/resource-extraction layer is required;
- ecCodes is Apache-2.0 and libaec is BSD-2-Clause.

The ecCodes build is supplied the exact ecbuild 3.12.0 checkout externally. Evaluation must not allow ecCodes' fallback `FetchContent` path to resolve the mutable `3.12.0` tag.

### netCDF-Java 5.10.0

Current state: **Android native path blocked/unverified; decoding capability not rejected**.

The project is BSD-3-Clause. DRT 42 is not a pure-Java path: upstream uses JNA plus native libaec, while its published native-compression artifacts target desktop Linux/macOS/Windows rather than Android. An Android adoption would therefore require a custom libaec package and proof of JNA/native-resource extraction/loading on API 26 and `arm64-v8a`, in addition to all 16 KiB requirements.

### wgrib2 3.8.0

Current state: **licence composition review required before it can become a production candidate**.

For the measured DRT set, AEC support uses libaec directly. NCEPLIBS-g2c is optional for JPEG/PNG paths and is not required for measured DRT `{0,42}`.

The pinned tag has no conventional top-level `LICENSE` file. Its README carries a U.S. Department of Commerce disclaimer, while individual and third-party source components still need an explicit redistribution/composition review. Do not infer a single production licence from the README disclaimer alone.

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
