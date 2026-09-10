# Official GRIB normalization contract

Status: M1 production boundary

MeteoOne keeps GRIB decoding separate from canonical forecast semantics. A concrete GRIB decoder may change later, but it must emit `DecodedGribField` values that obey this contract before `OfficialGribForecastMapper` is called.

## Parameter and unit contract

The mapper accepts these exact decoded units:

| Canonical input | GRIB decoder unit |
| --- | --- |
| 2 m temperature | K |
| 2 m dew point | K |
| 2 m relative humidity | % |
| mean sea-level pressure | Pa |
| 10 m U/V wind | m/s |
| 10 m gust | m/s |
| precipitation accumulation | kg/m² |
| total cloud cover | % |
| visibility | m |

Conversions at the normalization boundary are deliberately small and explicit:

- `temperatureC = kelvin - 273.15`;
- `pressureSeaLevelHpa = pascal / 100`;
- for liquid-water equivalent, `1 kg/m² = 1 mm`, so the numerical precipitation value is unchanged.

For ECMWF, this contract refers to the recommended WMO GRIB2 total-precipitation encoding (`tp`, parameter database ID 228228, unit kg/m²), not the legacy/local ECMWF parameter ID 228 whose documented unit is metres.

A parameter/unit mismatch is rejected. The mapper does not guess units from magnitude.

## Wind vector

When both 10 m components are present:

- speed is `sqrt(u² + v²)`;
- direction is meteorological **from** direction, clockwise from north:
  `degrees(atan2(-u, -v)) mod 360`.

Therefore north/east/south/west winds map to 0/90/180/270 degrees respectively. A calm vector has speed 0 and no direction. If only one component is available, MeteoOne leaves both derived wind speed and direction unavailable rather than inventing the missing component.

## Relative humidity fallback

A directly decoded 2 m RH value takes precedence. If RH is absent but temperature and dew point are available, MeteoOne uses the Magnus approximation in Celsius:

`RH = 100 × exp(a × Td / (b + Td) - a × T / (b + T))`

with:

- `a = 17.625`;
- `b = 243.04 °C`.

The result is bounded to 0..100%. No RH is fabricated when either temperature or dew point is missing.

## Interval semantics

`DecodedGribField.intervalStart` is allowed only for:

- precipitation accumulation;
- wind-gust maximum.

The start must be strictly earlier than `validTime`. At the official-source mapper boundary, a non-null start must also be greater than or equal to that forecast's `modelRun`; an interval from an earlier run is malformed input and is rejected. The mapper converts accepted metadata to canonical `ForecastInterval(start, validTime)`.

Multiple GRIB messages for the same canonical parameter and valid time are **not** resolved in the mapper. They are rejected as duplicates. Provider-specific selection must first choose the correct product/time-range semantics. This is intentional: live NOAA probing has already measured multiple `APCP` and `TCDC` messages in a single field/level subset, so silently taking the first message would be unsafe.

Cloud cover currently has no canonical interval metadata. A provider selector must therefore select the intended instantaneous cloud-cover product before normalization; statistical cloud-cover products cannot be mixed in as if they were instantaneous.

## Partial data

M1 requires graceful partial-provider failure. Missing parameters are therefore allowed and remain `null`. Examples:

- ECMWF direct output may not provide visibility in the selected Open Data field set;
- a temporary field-level failure may leave wind unavailable while temperature remains usable.

Missing data is distinct from malformed data. Wrong units, duplicate parameters, impossible signs/percentages, unsupported ranges and valid times before the model run are rejected.

## Provenance

Only direct official providers may use this mapper:

- `NOAA_NOMADS` -> `NOAA_GFS`;
- `ECMWF_OPEN_DATA` -> `ECMWF_IFS`;
- `DWD_OPEN_DATA` -> `DWD_ICON`.

Open-Meteo and MET Norway do not enter through this direct-source mapper. Provider identity remains separate from model-family identity so alternate delivery paths never become duplicate fusion votes.
