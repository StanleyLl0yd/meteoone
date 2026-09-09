# M0 forecast fusion baseline

Status: Experimental

The M0 engine provides an explainable baseline for tests and research. Its weights are not a claim of measured forecasting skill.

## Independent evidence

Forecast provider and meteorological model family are separate concepts.

Multiple providers exposing the same known model family are consolidated into one evidence group before fusion. Unknown model families remain provider-specific until their provenance can be established.

## Scalar parameters

For each timestamp:

1. consolidate duplicate provider exposure inside each evidence group using the median of available values;
2. combine evidence-group values with an equal-weight mean;
3. preserve missing values rather than inventing defaults.

Equal weights are deliberate for M0. Empirical weights require backtest/verification evidence.

## Wind direction

Directions use circular averaging so values around north, such as 350° and 10°, combine near 0° rather than 180°.

## Conditions

Weather condition codes are not fused in M0. The fused condition remains `UNKNOWN` until a canonical condition resolver is defined from normalized weather parameters.

## Model agreement

Temperature spread across independent evidence groups produces a qualitative diagnostic:

- high: spread <= 1.5 °C;
- medium: spread <= 3.0 °C;
- low: spread > 3.0 °C;
- insufficient: fewer than two independent temperature signals.

These thresholds are an initial diagnostic only. They are not calibrated forecast probabilities and must not be presented as numeric confidence.

## Next validation

Backtests must evaluate at least:

- 0–6 h;
- 6–24 h;
- 24–48 h;
- 48–72 h.

Required skill dimensions include temperature, precipitation, wind, and pressure across representative Russian climate regions.
