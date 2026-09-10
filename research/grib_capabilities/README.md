# GRIB capability probe

This research tool measures the GRIB2 envelope requirements of MeteoOne's direct official forecast sources before a production decoder is selected.

It is deliberately **not** a GRIB value decoder. It only validates GRIB2 message framing and extracts the template numbers that define the grid, product and data representation from Sections 3, 4 and 5.

## Why this exists

NOAA/NCEP GFS, ECMWF IFS Open Data and DWD ICON are all GRIB-oriented, but they do not necessarily use the same grids, product templates or packing methods. Selecting a JVM/native decoder from documentation alone can produce a dependency that builds successfully yet cannot decode a production field.

The probe therefore records measured requirements first. A decoder implementation may be adopted only after it supports the measured matrix and satisfies MeteoOne's Android constraints.

## Ordinary CI

`research/grib_capabilities/tests` uses synthetic local GRIB2 fixtures only. It validates:

- GRIB2 message framing and exact declared lengths;
- Section ordering and required sections;
- optional Section 2 handling;
- extraction of Section 3 grid-definition template numbers;
- extraction of Section 4 product-definition template numbers;
- extraction of Section 5 data-representation template numbers;
- concatenated message boundaries;
- bounded bzip2 decompression;
- strict UTC/cycle/forecast-hour inputs;
- official-source HTTPS allowlisting and cross-host redirect rejection;
- strict HTTP response-size and range metadata helpers;
- bounded multi-message NOAA measurement;
- partial-evidence payload behavior.

Ordinary pull-request CI never depends on live weather-provider availability.

## Live evidence

`.github/workflows/grib-capability-probe.yml` is manual-only. A probe run accepts one common operational run and one common three-hour forecast step from 3 through 72 so all three model families can be sampled consistently.

The workflow requests representative fields from:

- NOAA/NCEP NOMADS GFS 0.25 degree point subsets;
- ECMWF IFS Open Data using `.index` offsets and HTTP byte ranges;
- DWD global ICON per-field bzip2-compressed GRIB2 files.

Network reads are bounded. Only HTTPS on the three explicit official hosts is accepted, cross-host redirects are rejected, and ECMWF range responses must return the requested `Content-Range`.

The first two live runs on 2026-09-10 (`06Z`, `f006`) measured that NOAA/NOMADS field/level subsets are not guaranteed to contain exactly one GRIB message: `APCP` and `TCDC` each returned two concatenated messages. Because the purpose of this tool is capability measurement rather than production field selection, the resilient probe accepts a hard maximum of four messages for any sampled NOAA field and records every message's PDT/DRT/GDT. Production adapters must later select the required semantic message explicitly; they must not treat all measured messages as interchangeable.

The resilient probe continues to the next provider after a provider-specific failure. `evidence.json` schema version 2 records `success`, provider-level `errors`, every successfully returned sample and the measured template summary. The workflow still exits non-zero if any provider failed, so partial evidence cannot be mistaken for a complete capability matrix.

The artifact contains:

- `evidence.json` with source/final URLs, response status, SHA-256, byte sizes, extracted templates and any provider-level failures;
- representative provider samples needed to reproduce the inspection;
- `SHA256SUMS` for all payload files except the manifest itself.

For DWD, the exact official `.grib2.bz2` response is retained as the reproducibility sample. The decompressed GRIB2 is inspected in memory and its size and SHA-256 are recorded in `evidence.json`, avoiding a duplicate large artifact copy while retaining a deterministic integrity check.

The upload step runs even after a probe failure so partial provider samples remain available for diagnosis. A failed run is not accepted as final capability evidence.

After a successful live run, commit only a compact evidence summary plus immutable run/artifact identifiers and digests needed for long-term architectural decisions. Do not turn large operational GRIB files into normal Git history. The workflow artifact itself is retained for 90 days and is evidence transport, not the permanent architecture record.

## Decoder acceptance gate

A production decoder is not accepted by this research code. A later implementation must demonstrate support for every measured template required by canonical forecast fields and, if native code is involved, also satisfy:

- Android API 26+;
- `arm64-v8a` support;
- 16 KB page-size compatibility;
- acceptable APK/AAB size impact;
- bounded memory/CPU behavior on malformed and normal inputs;
- compatible licensing and maintained upstream code.

Backend processing is a later M5 concern and must not be introduced merely to avoid resolving the M1 client-side direct-source boundary.
