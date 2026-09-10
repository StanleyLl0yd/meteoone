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

MeteoOne requests only the fields needed by the canonical forecast model and uses a bounded geographic subset rather than downloading the hundreds-of-megabytes global GRIB files.

Official references:

- <https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl>
- <https://nomads.ncep.noaa.gov/gribfilter.php>
- <https://nomads.ncep.noaa.gov/pub/data/nccf/com/gfs/prod/>

## ECMWF IFS Open Data

ECMWF Open Data publishes a free public subset of deterministic IFS output in GRIB2 at 0.25 degree resolution.

Current Cycle 50r1 behavior was re-verified on 2026-09-10. All four deterministic atmospheric cycles, 00, 06, 12 and 18 UTC, now use `stream=oper` and `type=fc`. The former `scda` stream for 06/18 UTC was discontinued in May 2026. Files from older cycles or third-party mirrors can retain the historical naming, but the M1 real-time direct adapter targets the current official path.

For the M1 0–72 h window, deterministic IFS output is available every three hours. MeteoOne therefore must not pretend that the official direct source is hourly. Hourly product normalization is a later data-mapping concern and must preserve accumulation semantics rather than fabricating observed model steps.

ECMWF publishes a JSON-lines `.index` beside each GRIB file. Every record describes one GRIB field and includes `_offset` and `_length`, allowing a client to retrieve selected fields with individual HTTP byte-range requests. The M1 planner therefore returns the bounded index request and the associated GRIB URI separately; field-range selection is a subsequent adapter stage.

Open-data reuse must preserve the applicable ECMWF attribution and licence requirements; current Open Data documentation identifies CC BY 4.0 together with ECMWF terms.

Official references:

- <https://www.ecmwf.int/en/forecasts/datasets/open-data>
- <https://confluence.ecmwf.int/spaces/DAC/pages/272310539/ECMWF+open+data+real-time+forecasts+from+IFS+and+AIFS>

## DWD ICON Open Data

DWD Open Data exposes global deterministic ICON output under per-cycle and per-variable directories. The global delivery uses an icosahedral grid and bzip2-compressed GRIB2 files named like:

`icon_global_icosahedral_single-level_YYYYMMDDHH_FFF_PARAMETER.grib2.bz2`

Live directories verified on 2026-09-10 expose 00, 06, 12 and 18 UTC cycles and hourly forecast steps through at least hour 72. MeteoOne's current required field set can be addressed through the official directories for 2 m temperature, 2 m dew point, 2 m relative humidity, mean-sea-level pressure, 10 m U/V wind, 10 m maximum wind, total precipitation, total cloud cover and weather code. Maximum 10 m wind is an interval product and begins at forecast hour 1 rather than hour 0.

Unlike NOMADS, the DWD global directories do not provide a point/geographic subset endpoint. A single compressed global field observed during the M1 source audit is commonly several MiB (for example roughly 3 MiB for T2M and roughly 4 MiB for some humidity/cloud/gust fields). Repeating full-global field downloads for many parameters across 73 forecast hours would therefore be unsuitable as a routine on-device transport strategy.

The M1 request planner intentionally plans one bounded field/hour request at a time and preserves the real official filenames. It does **not** imply that downloading the entire planned matrix on Android is acceptable. Before a production direct DWD adapter is enabled on-device, MeteoOne must establish a bounded spatial extraction strategy or explicitly route the direct official-source processing through the later MeteoOne backend while retaining Open-Meteo as the client fallback/cross-check path. This transport constraint must not be hidden by treating Open-Meteo as if it were DWD itself.

Global ICON spatial lookup also requires icosahedral grid geometry; DWD publishes time-invariant `clat` and `clon` fields for that grid. A decoder/spatial-selection implementation must account for those coordinates rather than treating global ICON as a regular latitude/longitude raster.

Official reference:

- <https://opendata.dwd.de/weather/nwp/icon/grib/>

## GRIB boundary

The three direct official source paths are GRIB-oriented, which justifies one shared data-layer decoding seam. This does not justify selecting a decoder library before Android compatibility, maintenance, licence, binary size and required GRIB2 template support are verified.

The intended boundary is:

```text
official provider request plan
    -> bounded HTTPS response
    -> GRIB field decoder
    -> provider-specific normalization/mapping
    -> SourceForecast
    -> ForecastFusionEngine
```

`GribFieldDecoder` and its decoded field types live in `:forecast:data`. They do not expose third-party decoder classes to `:forecast:domain` or `:core:model`.

Any future decoder implementation must support the actual templates/packing used by all required MeteoOne fields, reject malformed/truncated input, remain bounded in memory/CPU, and be verified for Android API 26+ before it is accepted.

## Transport rules

Direct source adapters must:

- use HTTPS only;
- enforce explicit response-size bounds;
- request the smallest useful geographic/field subset that the provider actually exposes;
- avoid bulk global downloads on-device merely because an official server makes them addressable;
- obey provider pacing/rate-limit guidance;
- propagate cancellation once asynchronous networking enters scope;
- map provider failures into data-layer errors rather than provider exceptions in domain/UI;
- never require embedding secret provider credentials in the application;
- preserve provider and model-family provenance separately.

These rules do not change the M0 equal-weight model-family fusion baseline.
