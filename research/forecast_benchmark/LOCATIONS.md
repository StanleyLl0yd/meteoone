# Verification locations

MeteoOne keeps two distinct location sets so current verification can expand without rewriting the completed M0 evidence base.

## Current standing verification set

`locations.json` is the default current verification set. As of 2026-09-10 it contains 17 geographically and climatically diverse points:

| ID | City | Region / purpose |
| --- | --- | --- |
| `saint-petersburg` | Saint Petersburg | Baltic / maritime-influenced north-west Russia |
| `moscow` | Moscow | central European Russia |
| `kazan` | Kazan | Volga continental |
| `yekaterinburg` | Yekaterinburg | Urals continental |
| `novosibirsk` | Novosibirsk | West Siberia |
| `krasnoyarsk` | Krasnoyarsk | Central Siberia |
| `sochi` | Sochi | Black Sea / complex terrain |
| `vladivostok` | Vladivostok | Pacific / monsoon influence |
| `yakutsk` | Yakutsk | extreme continental |
| `murmansk` | Murmansk | Arctic / maritime influence |
| `tbilisi` | Tbilisi | South Caucasus valley terrain |
| `yerevan` | Yerevan | South Caucasus highland / dry continental |
| `tokyo` | Tokyo | Pacific humid subtropical / monsoon |
| `new-york` | New York | North American Atlantic coast |
| `paris` | Paris | western European oceanic |
| `berlin` | Berlin | central European transition climate |
| `dubai` | Dubai | Arabian Gulf hot desert |

These points are intended for M1 live source validation, cross-provider comparison, mapper regressions and later verification campaigns. Their presence does not retroactively change any completed M0 metric.

## Frozen M0 Russia set

`locations_m0_russia.json` preserves the exact 10-point location input used by the completed August 2026 M0 campaign. Its byte content is pinned by `test_locations.py` with SHA-256:

`42a3306a2e096bff1eec7bfece17b930a85cdc8594528c0193d780c63dd3c030`

The historical campaign remains:

- 28 initialization dates;
- 10 Russian locations;
- 3 independent model families;
- 840 archived deterministic forecasts;
- 00 UTC initialization;
- 72-hour horizon.

Do not describe M0 as a 17-location benchmark and do not recompute its published metrics merely because the standing verification set later expanded.

## Reproducing the M0 location selection

`batch-runs` already supports repeatable `--location`, so no second location-file CLI mode is needed. To select exactly the historical M0 geography, pass the original ten IDs explicitly:

```bash
python3 -m research.forecast_benchmark.cli batch-runs \
  --start 2026-08-01 \
  --end 2026-08-28 \
  --cycles 0 \
  --hours 72 \
  --location saint-petersburg \
  --location moscow \
  --location kazan \
  --location yekaterinburg \
  --location novosibirsk \
  --location krasnoyarsk \
  --location sochi \
  --location vladivostok \
  --location yakutsk \
  --location murmansk \
  --output research-output/m0-forecasts.jsonl
```

The frozen JSON is a reference artifact and programmatic input for audit/tests; the existing explicit CLI selector remains the single command-line mechanism.
