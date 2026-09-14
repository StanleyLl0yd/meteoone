# M1 GRIB decoder candidate evaluation

This directory contains the research evidence for issue #82. The candidate evaluation is complete: **ecCodes 2.48.0 + libaec 1.1.4 is the selected M1 production decoder path**. Production integration remains a separate step and is owned by the next M1 slice; this research directory does not itself add the decoder to application dependencies.

## Immutable corpus

Candidate behavior is evaluated only against the successful issue #26 capability artifact pinned in `pins.json`:

- workflow run `34577654571`;
- source SHA `cc19d5d8fafa64c32a652fc0360ff56ddcfe967d`;
- artifact ID `10190349074`;
- artifact digest `sha256:5556b372bbf5f6b79ed3299597c2a92ae7ac6a0519b3916008972fcfc53fa5c7`;
- model run `2026-09-10T18:00:00Z`, forecast hour `6`;
- 28 measured samples;
- retained ECMWF index SHA-256 `2514ac28a8d00a725262dca79dc5db3ab6185a9ed1a273a6b147d0c1dd01e368`.

`verify_corpus.py` verifies the artifact manifest, exact file set, every file checksum, schema/run/hour/sample count, the measured provider template envelope and the retained ECMWF index before preparing any candidate input.

Measured decoder envelope:

| Provider | GDT | PDT | DRT | Transport boundary |
| --- | --- | --- | --- | --- |
| NOAA NOMADS | `0` | `0, 8` | `0` | GRIB2; concatenated messages occur in the measured corpus |
| ECMWF Open Data | `0` | `0, 8` | `42` | exact HTTP 206 ranges retained by #26 |
| DWD Open Data | `101` | `0, 8` | `42` | outer bzip2; decompression remains outside the GRIB decoder |

The prepared six-sample smoke set covers temperature and precipitation for NOAA, ECMWF and DWD. DWD `.bz2` files are decompressed only by the research preparation boundary, then re-inspected with MeteoOne's bounded GRIB2 inspector before a decoder sees them. The evaluator also locates a measured concatenated NOAA sample and requires every message to value-decode.

## Final candidate state

All upstream source pins are immutable commit SHAs in `pins.json`.

### ecCodes 2.48.0 + libaec 1.1.4

Status: **selected for M1 production integration**.

Reasons:

- direct C API;
- value-decodes the complete measured GDT/PDT/DRT envelope;
- DRT 42 is provided through libaec;
- no JNA/resource-extraction layer is required;
- ecCodes is Apache-2.0 and libaec is BSD-2-Clause;
- exact API 26 `arm64-v8a` build passes;
- produced native ELF and package layout are 16 KiB compatible;
- real API 26 Android runtime execution passes;
- real Android 15 16 KiB-page runtime execution passes;
- malformed DRT 42 input fails in a controlled path;
- resource and binary-size consequences are measured and recorded.

The ecCodes build is supplied the exact ecbuild 3.12.0 checkout externally. Evaluation must not allow ecCodes' fallback `FetchContent` path to resolve a mutable tag.

### netCDF-Java 5.10.0

Status: **blocked fallback; not selected**.

The project is BSD-3-Clause, but DRT 42 is not a pure-Java path: upstream `Grib2DataReader` uses JNA plus native libaec. Upstream native artifacts do not provide an Android path. Android adoption would require a custom libaec package plus proof that the JNA loading path works on API 26/arm64 and through the same 16 KiB gates. That extra loading/runtime surface offers no demonstrated advantage over the selected narrower ecCodes path.

### wgrib2 3.8.0

Status: **not advanced in M1; not selected**.

Exact-source review found that DRT 42 handling uses `USE_G2CLIB_LOW` and `g2c_dec_aec` from NCEPLIBS-g2c 2.3.0. The stock library target also carries a broader native surface and licence/maintenance obligations, including GPL-3.0-or-later source and LGPL-3.0 g2c. A separately reviewed source-pruned fork could be a future distinct candidate, but M1 has no evidence that such a fork would improve on ecCodes + libaec.

This is an engineering distribution/maintenance decision, not a claim that GPL/LGPL software cannot be distributed on Android.

## Accepted selection evidence

Authoritative run:

- workflow run `34815805413` (`GRIB Decoder Candidate Evaluation` run #4);
- event `workflow_dispatch`;
- branch `main`;
- source SHA `64a595054200832c8d85dfc3d293eb0fa0f27c92`;
- conclusion `success`;
- evidence artifact ID `10336329301`;
- artifact name `meteoone-m1-grib-decoder-eval-34815805413`;
- artifact digest `sha256:8a8c0e427284c187e16aedac17deb33be1f4fe40637b111fd262554b7fa98497`.

### Host and malformed-input evidence

The run verified the exact #26 corpus, built the pinned host ecCodes/libaec stack, value-decoded all six representatives, value-decoded a measured concatenated NOAA file and rejected the deterministic truncated DRT 42 fixture with exit status `1`.

The representative envelope contains:

- GDT `0` and `101`;
- PDT `0` and `8`;
- DRT `0` and `42`.

### Android arm64 build/package evidence

The evaluator uses Android NDK `28.2.13676358`, API 26 and `arm64-v8a`.

Produced shared libraries:

| Library | Installed bytes |
| --- | ---: |
| `libeccodes.so` | 37,902,824 |
| `libaec.so` | 122,032 |
| `libsz.so` | 137,656 |

Every produced arm64 shared object reports AArch64 and every PT_LOAD alignment is `0x4000`. The package-shaped archive contains only these three libraries beneath `lib/arm64-v8a/` and passes `zipalign -P 16` verification. The archive reports 38,162,512 uncompressed bytes across its entries and SHA-256 `31f9a5a6a7e55f210dcb5e1a623207e57a3573a889c58323c3b66a4ec84ff496`.

No `.source.def` file is staged as a native library and no runtime binary requires an unstaged `libc++_shared.so`.

### API 26 runtime evidence

The x86_64 API 26 runtime decoded the exact representative envelope and concatenated NOAA input successfully. Representative full-field decode peak RSS was approximately 45 MiB. The deterministic malformed DRT 42 fixture exited with controlled status `1`.

### Android 15 16 KiB runtime evidence

The API 35 `google_apis_ps16k` runtime reported:

- SDK `35`;
- architecture `x86_64`;
- `PAGE_SIZE=16384`.

The same exact representative values and concatenated NOAA behavior were reproduced on the 16 KiB runtime. Representative full-field peak RSS remained approximately 45-46 MiB. The malformed DRT 42 fixture again exited with controlled status `1`.

The x86_64 runtime evidence proves Android execution behavior; it does not replace the independent mandatory arm64 build/package evidence.

## Selection rationale

**ecCodes 2.48.0 + libaec 1.1.4 is selected for M1 production integration.** It is the only evaluated path that combines complete measured-template value decoding with a demonstrated API26 arm64 build, verified 16 KiB-compatible ELF/package layout, minimum-Android execution, real 16 KiB runtime execution, deterministic malformed-input failure and a narrow permissively licensed native stack.

The measured Android binary footprint is material, especially `libeccodes.so`, so production integration should expose only the narrow APIs and data required by MeteoOne and should not retain full multi-hour × multi-field matrices. #89 owns the coordinate-aware point-selection boundary and containment of native/full-grid representations inside `:forecast:data`.

## ecCodes smoke contract

`eccodes_smoke.c` deliberately exercises value decoding rather than metadata-only parsing. For every GRIB message it:

1. creates the handle with `codes_handle_new_from_file`;
2. reads GDT, PDT and DRT keys;
3. obtains the `values` size;
4. calls `codes_get_double_array("values", ...)` to force real field decode, including DRT 42;
5. reports message/value counts, min/max, CPU time and maximum RSS;
6. fails on decoder errors, zero messages, zero values, allocation failure or no finite decoded values.

The manual workflow runs this contract over all six representatives and over a measured concatenated NOAA file, then truncates a representative DRT 42 GRIB and requires controlled failure.

## Malformed-input and fuzz/sanitizer strategy

The deterministic truncated DRT 42 case is the first regression gate. Production native integration must additionally:

- add a host Clang ASan+UBSan build of the narrow decode entry point;
- seed mutation/fuzz runs with the exact #26 representatives, including concatenated NOAA and decompressed DWD representatives;
- include truncation, section-length corruption, duplicated/reordered sections, invalid template identifiers, bitmap/value-count inconsistencies and random byte mutations;
- enforce bounded input size and bounded allocation policy around the native boundary;
- preserve crashing/minimized cases as regression fixtures when redistribution permits;
- run longer native fuzz targets outside normal PR CI when their duration/resource use is unsuitable for the standard gate.

A production JNI boundary must validate Kotlin/Java lengths and ownership before native calls and convert decoder failures into bounded provider errors rather than process termination.

## Evidence produced by the manual workflow

`.github/workflows/grib-decoder-candidate-eval.yml` remains `workflow_dispatch`-only. It records immutable corpus/run metadata, exact upstream source SHAs, host build logs, value-decode output, malformed-input result, Android `arm64-v8a` build logs, ELF headers/program headers/dynamic dependencies, native sizes, 16 KiB package-alignment verification, KVM state, API26 runtime evidence and Android 15 ps16k runtime evidence. Evidence upload runs under `if: always()` so failed future evaluations remain inspectable.
