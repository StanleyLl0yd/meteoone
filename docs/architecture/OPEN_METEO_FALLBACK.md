# Open-Meteo production fallback boundary

Status: M1 implementation baseline

Open-Meteo is a fallback, normalization, and cross-check provider path. It is not an additional meteorological model family merely because it delivers the data through a different API.

## Explicit model paths

MeteoOne requests exactly one Open-Meteo model per response:

| Open-Meteo model ID | MeteoOne model family |
| --- | --- |
| `ecmwf_ifs` | `ECMWF_IFS` |
| `icon_global` | `DWD_ICON` |
| `ncep_gfs_global` | `NOAA_GFS` |

A direct NOAA GFS forecast and an Open-Meteo GFS forecast therefore retain different `ForecastProvider` values but the same `ModelFamily.NOAA_GFS`. The fusion engine continues to treat that family as one independent evidence vote.

## Request contract

`OpenMeteoForecastRequestPlanner` accepts only `ForecastCoordinate`, so raw device coordinates never enter this provider request boundary. It plans one HTTPS request to `api.open-meteo.com/v1/forecast` with:

- one explicit model ID;
- exactly 72 hourly forecast steps;
- UTC timestamps represented as Unix seconds;
- Celsius temperatures;
- metres-per-second wind speed;
- millimetre precipitation;
- a 512 KiB response bound.

`OpenMeteoForecastRequest` re-parses the URI query and requires it to match the request's explicit model, normalized coordinate, exact field set, 72-hour horizon, UTC/time format, and units. Duplicate, missing, unexpected, or semantics-changing query parameters fail closed, while parameter ordering is irrelevant. This prevents URI data for one model or location from being normalized under another request identity.

The selected hourly fields cover the current canonical M1 weather surface: temperature, apparent temperature, dew point, relative humidity, mean sea-level pressure, wind speed/direction/gust, precipitation, cloud cover, visibility, and WMO weather code.

Precipitation probability is intentionally not requested. Open-Meteo can derive precipitation probabilities from ensemble products, so assigning that value to a deterministic `ECMWF_IFS`, `DWD_ICON`, or `NOAA_GFS` provenance would blur the model-family evidence boundary.

## Normalization contract

`OpenMeteoForecastMapper` is a bounded JSON-to-domain mapper. It validates the response coordinate range, zero UTC offset, time and field units, exact 72-point hourly cadence, finite numeric values, and conservative physical ranges before returning `SourceForecast`.

Open-Meteo responses may expose a requested single-model series either with its base field name or with the selected model ID appended. The mapper accepts only the base name or the suffix for the model carried by the request; a different model suffix or ambiguous duplicate series fails closed. `temperature_2m` is required as the minimal forecast series. Other requested fields may be unavailable for a particular model and then remain `null` in the canonical forecast rather than being fabricated or borrowed from another source.

The normalized forecast uses:

- `ForecastProvider.OPEN_METEO`;
- the explicitly selected existing `ModelFamily`;
- `modelRun = null`, because the live Forecast API response does not identify the source model initialization time and MeteoOne must not infer one from wall-clock time;
- the caller-provided privacy-normalized forecast location; Open-Meteo's returned grid-cell coordinate is validated but is not treated as a new user location;
- one-hour precipitation intervals ending at each forecast timestamp, matching the API's preceding-hour precipitation-sum semantics;
- model-specific wind-gust intervals ending at each forecast timestamp: one hour for NOAA GFS and DWD ICON Global, three hours for ECMWF IFS, matching the corresponding Open-Meteo model documentation;
- no wind direction when the normalized provider wind speed is exactly `0.0`; this removes undefined calm-wind direction without introducing a meteorological calm threshold, while a missing wind-speed value does not erase an independently present direction;
- coarse `WeatherCondition` values derived from WMO weather codes.

Missing JSON values remain missing. A missing gust therefore has no interval metadata. Unknown weather codes normalize to `WeatherCondition.UNKNOWN`; values are never fabricated from another provider.

## Scope boundary

This M1 slice does not execute HTTP requests and does not introduce coroutine, cancellation, retry/backoff, rate-limit, provider-health, persistence, cache, or stale-data policy. The request object only carries the URI, explicit model identity, normalized coordinate, and hard response-size bound needed by a later transport executor.

Official references:

- <https://open-meteo.com/en/docs>
- <https://open-meteo.com/en/docs/ecmwf-api>
- <https://open-meteo.com/en/docs/dwd-api>
- <https://open-meteo.com/en/docs/gfs-api>
