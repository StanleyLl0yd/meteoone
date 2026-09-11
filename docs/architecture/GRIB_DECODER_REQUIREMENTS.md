# GRIB decoder capability gate

Status: M1 measured-evidence gate satisfied; no production decoder selected.

This document records the minimum GRIB2 capabilities demonstrated by MeteoOne's official-source probes and the Android acceptance gates that any future decoder candidate must pass. It deliberately does not select or add a decoder dependency. Issue #26 remains the authority for decoder-candidate evaluation.

## Measured minimum envelope

The current lower bound comes from immutable manual probe artifacts produced from official NOAA/NOMADS, ECMWF Open Data and DWD Open Data endpoints.

| Requirement | Measured evidence |
| --- | --- |
| Grid Definition Templates | GDT `0` and `101` |
| Product Definition Templates | PDT `0` and `8` |
| Data Representation Templates | DRT `0` and `42` |
| Multiple GRIB messages | required; NOAA subsets can contain concatenated messages for one requested parameter |
| Outer compression | DWD ICON fields require bzip2 decompression before GRIB2 inspection |
| Indexed byte ranges | ECMWF field transport uses validated `.index` offsets and bounded single-field HTTP ranges |

Representative evidence recorded in issue #26:

- workflow run `34471319336`, `main=9365f09ac5fc9ba19e263af21b4fd5a77a48f6d6`, common run `2026-09-10T06:00Z`, `f006`: NOAA measured `GDT 0 / DRT 0 / PDT {0,8}`; DWD measured `GDT 101 / DRT 42 / PDT {0,8}` with outer bzip2;
- workflow run `34471634298`, same then-current main, common run `2026-09-10T00:00Z`, `f006`: ECMWF indexed range transport succeeded for retained `2t`, `2d`, `msl`, `10u`, and `10v` samples, all measured as `GDT 0 / PDT 0 / DRT 42`;
- workflow run `34577654571`, `main=cc19d5d8fafa64c32a652fc0360ff56ddcfe967d`, tree `7efe9b6731168fd46c44af2699baf630e2164f0d`, common run `2026-09-10T18:00Z`, `f006`: successful three-provider artifact `10190349074`, digest `sha256:5556b372bbf5f6b79ed3299597c2a92ae7ac6a0519b3916008972fcfc53fa5c7`, `success=true`, 28 samples and no errors. The retained ECMWF index is 40,025 bytes with SHA-256 `2514ac28a8d00a725262dca79dc5db3ab6185a9ed1a273a6b147d0c1dd01e368`. NOAA measured `GDT 0 / DRT 0 / PDT {0,8}`, ECMWF measured `GDT 0 / DRT 42 / PDT {0,8}`, and DWD measured `GDT 101 / DRT 42 / PDT {0,8}` with outer bzip2.

The successful current-main artifact satisfies the measured-evidence hard gate and does not expand the previously documented template envelope.

## Decoder candidate matrix

Upstream state rechecked on 2026-09-11. Versions are observations, not dependency pins.

| Candidate | Relevant capability | Native-code consequence | Licence/distribution note | M1 status |
| --- | --- | --- | --- | --- |
| netCDF-Java 5.10.0 | GRIB2 GDT 101 and DRT 42 support landed in 5.8.0 | DRT 42 support uses native `libaec`; Android still needs a verified native packaging/load path | netCDF-Java is BSD-3-Clause and ships third-party components with their own licences; `libaec` is BSD-2-Clause | Evidence gate passed; Android smoke still required |
| ECMWF ecCodes 2.48.0 | Reference-grade GRIB1/2 implementation; CCSDS/AEC available through `libaec` | Native C/C++; no MeteoOne-verified Android package/JNI boundary | Apache-2.0; `libaec` is BSD-2-Clause | Evidence gate passed; Android smoke still required |
| NOAA wgrib2 3.8.0 | AEC support through NCEPLIBS-g2c 2.3.0; C library interface available | Native C toolchain/library plus transitive native dependencies | wgrib2 contains public-domain and GNU-licensed modules; NCEPLIBS-g2c packaging is LGPL-3.0-only in current distribution metadata. Licence composition requires explicit review before app distribution | Evidence gate passed; Android smoke and licence review still required |

The matrix intentionally treats native AEC support as part of the candidate, not as an implementation detail that can be ignored: DRT 42 is present in measured ECMWF and DWD production data.

## Android native acceptance gate

Any candidate that reaches native code must pass all of the following before production adoption:

1. Build and run on MeteoOne's minimum Android API 26.
2. Provide a reproducible `arm64-v8a` build; additional production ABIs are evaluated separately when product scope requires them.
3. Build native shared objects with a modern Android NDK configuration compatible with 16 KiB page-size devices. NDK r28 enables flexible 16 KiB page-size compatibility by default, but that does not replace verification of the produced binaries and package.
4. Verify ELF segment alignment and APK/AAB native-library packaging, then execute an actual 16 KiB page-size runtime smoke test.
5. Decode the exact immutable NOAA, ECMWF and DWD samples from successful current-main artifact `10190349074`, including concatenated messages, GDT 101, PDT 8, DRT 42 and DWD bzip2 input handling at the MeteoOne boundary.
6. Demonstrate bounded memory and CPU behavior on the measured field-size envelope. A decoder must not turn DWD's global-field transport into permission to retain or decode an unbounded 73-hour × multi-field matrix on-device.
7. Reject malformed/truncated data without process corruption. Native candidates require malformed-input tests and a fuzzing/sanitizer strategy appropriate to the JNI/native boundary.
8. Keep third-party/native types inside `:forecast:data`; `:forecast:domain` and `:core:model` must continue to expose only MeteoOne-owned forecast types.
9. Record dependency, transitive dependency, licence/notice, binary-size and update/maintenance consequences before merge.

## Current candidate-evaluation gate

The measured-evidence hard stop is satisfied by run `34577654571` and artifact `10190349074`. Candidate smoke testing may now begin against those exact immutable samples.

No candidate may be selected, pinned or introduced into production dependencies until its Android path has passed the applicable acceptance checks above. Native candidates in particular must not be adopted on desktop-only decode success.

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
