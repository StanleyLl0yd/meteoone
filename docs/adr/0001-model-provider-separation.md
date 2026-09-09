# ADR-0001: Separate forecast models from providers

Status: Accepted

## Context

Weather APIs may expose forecasts derived from the same underlying meteorological model. Treating every API response as an independent vote would overweight duplicated model information.

## Decision

MeteoOne represents provider and model family separately. Fusion logic weights meteorological evidence with awareness of model-family correlation.

Provider-specific DTOs remain in the data layer and are normalized before entering the domain.

Provider diversity and model diversity are different properties. MeteoOne should progressively consume NOAA/NCEP GFS, ECMWF IFS Open Data, and DWD ICON Open Data through direct official-source adapters while retaining Open-Meteo as a fallback, normalization, and cross-check path.

The same model family received through an official source and through Open-Meteo is still one meteorological evidence group. Provider fallback or cross-checking must never turn duplicate delivery paths into duplicate fusion votes.

## Consequences

- Adding a new API does not automatically add an independent ensemble member.
- Provider independence improves resilience and provenance without changing model-family vote count.
- Open-Meteo must not remain the only long-term delivery path for ECMWF, ICON, and GFS.
- Source comparison can show both provider and model origin.
- Fusion and verification metrics can be calculated by model family, provider pipeline, parameter, location, and lead time.
