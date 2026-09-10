# Location boundary

Status: M1 implementation baseline

MeteoOne resolves a forecast coordinate before provider requests are planned.

```text
foreground coarse device location ─┐
                                   ├─> 0.1° normalization -> ForecastCoordinate
manual fallback coordinates ──────┘
```

## Privacy and permission policy

- Current-location acquisition uses Android framework `LocationManager` and only `ACCESS_COARSE_LOCATION`.
- Precise (`ACCESS_FINE_LOCATION`) and background location permissions are outside the current scope.
- Permission-request and onboarding UI belongs to M3; the M1 data boundary reports `PERMISSION_REQUIRED` without presenting UI.
- Raw `android.location.Location` values never leave `:core:location`.
- Raw device or manual latitude/longitude values are not retained after normalization.
- `ForecastCoordinate` is rounded to 0.1 degree and is the only coordinate type intended to cross the module boundary.
- No location value is persisted, logged, sent to analytics, or attached to diagnostics in this module.

## Acquisition behavior

`AndroidCurrentLocationClient` performs one foreground request against the Android network location provider. The request is bounded to 15 seconds by default, is cancellable, and delivers at most one result. Failures are reduced to a small typed reason set and do not expose platform exceptions or raw location data.

A disabled/unavailable provider, denied permission, timeout, platform failure, or cancellation is not fatal to the product. The caller may select `ForecastLocationTarget.Manual`; construction immediately applies the same normalization policy.

The network provider is intentionally preferred over requesting precise GPS because the current global forecast models do not need GPS-level precision. If a future provider or product feature genuinely needs finer resolution, the permission and persistence implications require a new privacy/security review before changing this boundary.

## Forecast integration

`ForecastCoordinate` is a request/cache identity, not yet a full `ForecastLocation`. Provider normalization may later resolve elevation and time-zone metadata and may snap the coordinate further to a provider/model grid. It must not infer or restore the original device precision.

This module does not own geocoding, UI, persistence, background tracking, or provider networking.
