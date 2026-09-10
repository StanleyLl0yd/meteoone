# Forecast source boundary

Status: M1 implementation baseline

MeteoOne separates a forecast model from the service that delivers that model. A provider path is provenance, not automatically an independent forecast signal.

## Source matrix

| Provider path | Model family | Production role | Primary transport |
| --- | --- | --- | --- |
| NOAA/NCEP NOMADS | NOAA GFS | direct official source | HTTPS GRIB2 subset |
| ECMWF Open Data | ECMWF IFS | direct official source | HTTPS GRIB2 / indexed byte ranges |
| DWD Open Data | DWD ICON | direct official source | HTTPS GRIB2 files |
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

ECMWF Open Data publishes a free public subset of IFS output. The operational open-data delivery is GRIB2 at 0.25 degree resolution unless a product states otherwise.

ECMWF publishes a JSON-lines `.index` beside GRIB files. Each index record describes a GRIB field and provides byte offset/length information, allowing a client to retrieve selected fields with HTTP byte-range requests instead of downloading a whole file. MeteoOne should exploit that property when the ECMWF adapter is implemented.

Open-data reuse must preserve the applicable ECMWF attribution and licence requirements; current Open Data documentation identifies CC BY 4.0 together with ECMWF terms.

Official references:

- <https://www.ecmwf.int/en/forecasts/datasets/open-data>
- <https://confluence.ecmwf.int/display/UDOC/ECMWF+Open+Data+-+Real+Time>

## DWD ICON Open Data

DWD Open Data exposes global ICON forecast GRIB output under per-cycle and per-variable directories. Relevant global variables include 2 m temperature/dew point/relative humidity, mean-sea-level pressure, total precipitation, 10 m U/V wind, 10 m maximum wind and total cloud cover.

The direct adapter should request only the files/fields required for MeteoOne and normalize them behind the same data-layer boundary as NOAA and ECMWF. DWD transport/file naming stays provider-specific and must not leak into domain or UI code.

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
- request the smallest useful geographic/field subset;
- obey provider pacing/rate-limit guidance;
- propagate cancellation once asynchronous networking enters scope;
- map provider failures into data-layer errors rather than provider exceptions in domain/UI;
- never require embedding secret provider credentials in the application;
- preserve provider and model-family provenance separately.

These rules do not change the M0 equal-weight model-family fusion baseline.
