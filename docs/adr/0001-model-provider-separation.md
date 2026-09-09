# ADR-0001: Separate forecast models from providers

Status: Accepted

## Context

Weather APIs may expose forecasts derived from the same underlying meteorological model. Treating every API response as an independent vote would overweight duplicated model information.

## Decision

MeteoOne represents provider and model family separately. Fusion logic weights meteorological evidence with awareness of model-family correlation.

Provider-specific DTOs remain in the data layer and are normalized before entering the domain.

## Consequences

- Adding a new API does not automatically add an independent ensemble member.
- Source comparison can show both provider and model origin.
- Fusion and verification metrics can be calculated by model family, provider pipeline, parameter, location, and lead time.
