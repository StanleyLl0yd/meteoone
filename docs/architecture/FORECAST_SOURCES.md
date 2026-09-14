# Forecast source boundary

Status: M1 implementation baseline

MeteoOne separates a forecast model from the service that delivers that model. A provider path is provenance, not automatically an independent forecast signal.

## Source matrix

| Provider path | Model family | Production role | Primary transport |
| --- | --- | --- | --- |
| NOAA/NCEP NOMADS | NOAA GFS | direct official source | HTTPS GRIB2 subset |
| ECMWF Open Data | ECMWF IFS | direct official source | HTTPS GRIB2 / indexed byte ranges |
| DWD Open Data | DWD ICON | direct official source | HTTPS bzip2-compressed GRIB2 files |
| Open-Meteo | varies by requested model | fallback, normalization and cross-check | HTTPS API |
| MET Norway | underlying global model provenance applies | correlated/fallback evidence where used | HTTPS API |

The same model family delivered by two provider paths remains one independent fusion evidence group. For example, GFS delivered by NOMADS and GFS delivered by Open-Meteo are two provider observations of `NOAA_GFS`, not two ensemble members.

Direct-source plans share one provider/model/time metadata guard backed by `OfficialProviderIdentity`. Provider-specific plans additionally bind their actual request URI and transport bounds to the planned run, forecast hour, grid point or field as applicable, so unrelated bytes cannot retain trusted official provenance after manual construction or data-class copying.

The M1 production engine selects one conservative 00/06/12/18 UTC operational cycle at least seven hours behind its injected generation time for its bounded direct-official cross-checks. It does not probe newer cycles, sleep for publication, retry another run, or maintain provider-health/rate-limit state. NOAA and DWD use the next hourly valid step from that run; ECMWF aligns to the next supported three-hour direct step.

## NOAA/NCEP GFS

M1 uses the official NOMADS GFS 0.25 degree GRIB Filter path.

Observed against the live service on 2026-09-10:

- the filter exposes `gfs.YYYYMMDD` directories and GFS 0.25 degree GRIB2 subsets;
- operational cycles are 00, 06, 12 and 18 UTC;
- product names use `gfs.tCCz.pgrb2.0p25.fFFF`;
- the live inventory contains consecutive hourly products including `f001`, `f002`, and through at least `f072`, so MeteoOne's M1 0–72 h hourly horizon can be requested without inventing intermediate GFS times;
- GRIB Filter supports parameter, level and geographic subsetting;
- NOMADS asks automated looping clients to pause between repeated requests; MeteoOne records a 10 second minimum request-spacing policy in its request plan;
- the former NOMADS OPeNDAP path explicitly reports that OPeNDAP format has been retired and must not be used as the production integration path.

`NoaaGfsRequestPlan` binds the exact bounded NOMADS request to its run, forecast hour and snapped source-grid point. The request's fixed field/level set, 2 MiB response bound and 10 second minimum spacing therefore cannot drift independently from the plan metadata.

MeteoOne requests only the fields needed by the canonical forecast model and uses a bounded geographic subset rather than downloading the hundreds-of-megabytes global GRIB files. The M1 production engine executes one such point-subset request as the direct GFS cross-check; it does not loop through the complete 72-hour NOMADS series and therefore does not implement a pacing scheduler in M1.

Official references:

- <https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl>
- <https://nomads.ncep.noaa.gov/gribfilter.php>
- <https://nomads.ncep.noaa.gov/pub/data/nccf/com/gfs/prod/>

## ECMWF IFS Open Data

ECMWF Open Data publishes a free public subset of deterministic IFS output in GRIB2 at 0.25 degree resolution.

Current Cycle 50r1 behavior was re-verified on 2026-09-10. All four deterministic atmospheric cycles, 00, 06, 12 and 18 UTC, now use `stream=oper` and `type=fc`. The former `scda` stream for 06/18 UTC was discontinued in May 2026. Files from older cycles or third-party mirrors can retain the historical naming, but the M1 real-time direct adapter targets the current official path.

For the M1 0–72 h window, deterministic IFS output is available every three hours. MeteoOne therefore must not pretend that the official direct source is hourly. The production direct cross-check is explicitly aligned to a real three-hour IFS step; the complete hourly M1 horizon is supplied by the model-specific normalized delivery paths rather than fabricated direct IFS timestamps.

`EcmwfIfsRequestPlan` validates ECMWF Open Data / IFS provenance, requires its `validTime` to equal `modelRun + forecastHour`, and binds both the `.index` request and companion `.grib2` URI to that exact run/hour under the official endpoint. The 2 MiB index-response bound is part of the plan invariant; operational cycle, horizon and three-hour cadence policy remain planner responsibilities.

ECMWF publishes a JSON-lines `.index` beside each GRIB file. Every record describes one GRIB field and includes `_offset` and `_length`, allowing a client to retrieve selected fields with individual HTTP byte-range requests. The M1 data layer parses that index under a 2 MiB bound, validates the expected deterministic `domain=g`, `class=od`, `type=fc`, `stream=oper`, model date/cycle and forecast step, and only then emits a bounded single-field `Range` request. Multipart ranges are intentionally not assumed because ECMWF notes that they are not supported by all servers.

Each emitted `EcmwfFieldRangePlan` is non-copyable outside its validated data-layer construction path and revalidates ECMWF provenance, forecast-time consistency, the canonical GRIB URI, exact `request.maxResponseBytes == range.length`, and the 16 MiB per-field ceiling. The field-to-range association itself remains the responsibility of the validated `.index` selector, where the parameter metadata and byte offsets are available together.

The current surface-field selection boundary is explicit:

- `2t` — 2 m temperature;
- `2d` — 2 m dew point;
- `msl` — mean sea-level pressure;
- `10u` / `10v` — 10 m wind components;
- `10fg` / `10fg3` — maximum 10 m wind gust over the preceding post-processing interval;
- `tp` — total precipitation;
- `tcc` — total cloud cover.

ECMWF's current open-data catalogue names the gust field `10fg` and notes that it appears as `10fg3` for forecast steps 3 through 144. ECMWF's GRIB2 migration documentation maps the legacy gust encodings to `max_i10fg` with an explicit time span. The M1 research probe recognizes all three documented identities so a live run can diagnose the actual schema, but the production selector currently accepts only `10fg` and `10fg3`. `max_i10fg` remains fail-closed until the exact live index representation of its time-span metadata is measured and validated. All production aliases still require exactly one matching surface entry; aliases never relax duplicate detection.

Relative humidity is not requested as a separate IFS field in this slice because it can be derived later from temperature and dew point with an explicitly tested mapper. Visibility is not claimed from the current IFS Open Data selection until a matching official field is verified. Missing fields therefore remain missing instead of being fabricated or borrowed from another provider under ECMWF provenance.

The index parser ignores unrelated JSON keys but fails closed on malformed records, missing/invalid byte ranges, arithmetic overflow, duplicate selected fields, wrong level type, wrong run provenance or selected fields larger than the configured single-field transport bound.

The M1 production engine uses the validated index only to select the `2t` range for one current-horizon direct cross-check. It performs one exact byte-range request and does not expand that cross-check into a 72-hour by field matrix on-device.

Open-data reuse must preserve the applicable ECMWF attribution and licence requirements; current Open Data documentation identifies CC BY 4.0 together with ECMWF terms.

Official references:

- <https://www.ecmwf.int/en/forecasts/datasets/open-data>
- <https://confluence.ecmwf.int/spaces/DAC/pages/272310539/ECMWF+open+data+real-time+forecasts+from+IFS+and+AIFS>
- <https://confluence.ecmwf.int/spaces/MTG2US/pages/554148197/Migration+to+GRIB2+-+changes+to+encoding+of+parameters>

## DWD ICON Open Data

DWD Open Data exposes global deterministic ICON output under per-cycle and per-variable directories. The global delivery uses an icosahedral grid and bzip2-compressed GRIB2 files named like:

`icon_global_icosahedral_single-level_YYYYMMDDHH_FFF_PARAMETER.grib2.bz2`

Live directories verified on 2026-09-10 expose 00, 06, 12 and 18 UTC cycles and hourly forecast steps through at least hour 72. MeteoOne's current required field set can be addressed through the official directories for 2 m temperature, 2 m dew point, 2 m relative humidity, mean-sea-level pressure, 10 m U/V wind, 10 m maximum wind, total precipitation, total cloud cover and weather code. Maximum 10 m wind is an interval product and begins at forecast hour 1 rather than hour 0.

`DwdIconRequestPlan` validates DWD Open Data / ICON provenance, requires `validTime == modelRun + forecastHour`, enforces each field's intrinsic first available forecast hour, and binds the exact official field URI plus 8 MiB compressed-response bound to the same run/hour/field metadata. Operational-cycle and M1 horizon policy remain planner responsibilities.

`DwdIconGridGeometryPlan` separately requires an exact operational ICON model run and binds its time-invariant `clat` and `clon` requests to the canonical official paths for that run with the existing exact 4 MiB response bounds, so either geometry request cannot drift independently from the run context.

Unlike NOMADS, the DWD global directories do not provide a point/geographic subset endpoint. A single compressed global field observed during the M1 source audit is commonly several MiB (for example roughly 3 MiB for T2M and roughly 4 MiB for some humidity/cloud/gust fields). Repeating full-global field downloads for many parameters across 73 forecast hours would therefore be unsuitable as a routine on-device transport strategy.

The M1 request planner intentionally plans one bounded field/hour request at a time and preserves the real official filenames. The M1 production engine permits exactly one bounded `T_2M` field/hour download as a direct-source cross-check, followed by bounded bzip2 decompression, run-scoped CLAT/CLON geometry lookup, point selection and canonical mapping. This narrow cross-check proves the real official DWD execution path without turning addressability into a routine global-matrix forecast strategy.

Downloading the full planned DWD hour-by-field matrix on Android remains prohibited. A future complete direct-ICON forecast path still requires bounded spatial extraction or routing through the later MeteoOne backend; Open-Meteo remains the client path that can provide the complete hourly ICON-family horizon without pretending to be DWD provenance.

Global ICON spatial lookup requires icosahedral grid geometry; DWD publishes time-invariant `clat` and `clon` fields for that grid. The production decoder uses the run-bound geometry lifecycle rather than treating global ICON as a regular latitude/longitude raster.

Official reference:

- <https://opendata.dwd.de/weather/nwp/icon/grib/>

## GRIB boundary

The three direct official source paths are GRIB-oriented and share one data-layer decoding seam. Production M1 uses the bundled ecCodes JNI decoder selected after Android compatibility, licence, template/packing coverage, binary-size and native-boundary verification.

The production boundary is:

```text
official provider request plan
    -> bounded HTTPS response / indexed byte range
    -> bounded decompression when required
    -> ecCodes GRIB decode
    -> provider-bound point selection and semantic validation
    -> provider-specific normalization/mapping
    -> SourceForecast
    -> M1 production composition
    -> ForecastFusionEngine
```

`GribFieldDecoder`, decoded field types, native metadata, ecCodes definitions and full-grid representations live in `:forecast:data`. They do not expose third-party decoder classes or native/full-grid data to `:forecast:domain` or `:core:model`.

The decoder rejects malformed or semantically mismatched input, enforces bounded message/value counts, preserves provider/run/valid-time binding, and has production corpus plus Android-native evidence for the required NOAA, ECMWF and DWD paths. Exact duplicate decoded fields may collapse only when every canonical field property is identical; conflicting duplicates continue to fail closed.

## Transport rules

M1 executes planned provider fetches through the JVM-testable `:core:network` boundary. OkHttp is contained behind MeteoOne-owned request, response, failure and cancellation types and does not leak into forecast domain or presentation code.

The transport boundary:

- accepts HTTPS GET requests only and rejects user info, non-default explicit ports and unsafe headers;
- uses explicit connect/read/call timeouts, disables automatic redirects and disables automatic retry-on-connection-failure;
- requests `Accept-Encoding: identity` so byte-range and response-size semantics are not changed by transparent decoding;
- rejects declared response lengths above each request's ceiling and independently reads no more than one byte beyond the ceiling before failing oversized input;
- maps cancellation, raw I/O, malformed transport responses and oversize responses into MeteoOne-owned failure reasons.

`:forecast:data` adds provider-plan response validation on top of that generic transport. Ordinary NOAA, DWD, ECMWF-index and Open-Meteo requests accept only HTTP 200. ECMWF selected-field requests send the exact planned `Range`, accept only HTTP 206, require exactly one matching `Content-Range`, and require the returned body length to equal the selected range length. A redirect response is therefore rejected rather than followed.

Direct-source planning continues to preserve provider pacing guidance and per-request response ceilings. Minimum request spacing remains planner metadata in M1; no shared scheduler, retry/backoff state, provider-health state or cache policy is introduced by production execution.

Direct source adapters continue to request the smallest useful geographic/field subset that each provider actually exposes, avoid bulk global downloads on-device merely because an official server makes them addressable, never embed secret provider credentials, and preserve provider and model-family provenance separately.

These rules do not change the M0 equal-weight model-family fusion baseline.
