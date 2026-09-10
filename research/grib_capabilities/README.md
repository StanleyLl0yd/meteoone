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
- strict HTTP range metadata helpers.

Ordinary pull-request CI never depends on live weather-provider availability.

## Live evidence

`.github/workflows/grib-capability-probe.yml` is manual-only. A probe run accepts one common operational run and one common three-hour forecast step from 3 through 72 so all three model families can be sampled consistently.

The workflow requests representative fields from:

- NOAA/NCEP NOMADS GFS 0.25 degree point subsets;
- ECMWF IFS Open Data using `.index` offsets and HTTP byte ranges;
- DWD global ICON per-field bzip2-compressed GRIB2 files.

Network reads are bounded. Only HTTPS on the three explicit official hosts is accepted, cross-host redirects are rejected, and ECMWF range responses must return the requested `Content-Range`.

The artifact contains:

- `evidence.json` with source/final URLs, response status, SHA-256, byte sizes and extracted templates;
- provider samples needed to reproduce the inspection;
- `SHA256SUMS` for the artifact payload files.

For DWD, the official compressed `.grib2.bz2` response is the canonical downloaded sample. The decompressed GRIB2 content is inspected in memory and its SHA-256/size are recorded; retaining a second decompressed copy is unnecessary once the probe is finalized.

After a successful live run, commit only a compact evidence summary/artifact reference needed for long-term architectural decisions. Do not turn large operational GRIB files into normal Git history.

## Decoder acceptance gate

A production decoder is not accepted by this research code. A later implementation must demonstrate support for every measured template required by canonical forecast fields and, if native code is involved, also satisfy:

- Android API 26+;
- `arm64-v8a` support;
- 16 KB page-size compatibility;
- acceptable APK/AAB size impact;
- bounded memory/CPU behavior on malformed and normal inputs;
- compatible licensing and maintained upstream code.

Backend processing is a later M5 concern and must not be introduced merely to avoid resolving the M1 client-side direct-source boundary.
