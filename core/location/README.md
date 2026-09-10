# Core location

`:core:location` owns the M1 foreground location boundary before forecast requests are made.

Rules:

- Android location acquisition requests only `ACCESS_COARSE_LOCATION`.
- Background location is out of scope.
- Raw `android.location.Location` and raw latitude/longitude values stay inside the acquisition call and are not returned from this module.
- Device and manual coordinates are normalized to the current 0.1 degree forecast/cache grid before they can become a `ForecastCoordinate`.
- `ForecastCoordinate` is the only coordinate type intended to cross this module boundary.
- No location value is persisted or logged here.
- Permission-request/onboarding UI belongs to M3; M1 reports `PERMISSION_REQUIRED` instead of owning UI.
- If current location is unavailable, callers can use `ForecastLocationTarget.Manual`; manual coordinates are normalized at construction and the raw input is not retained.

The 0.1 degree normalization is a current M1 privacy/cache baseline for the global-model forecast set, not a claim about any provider's native grid. Provider-specific mapping may snap a normalized coordinate further, but must not recover or persist raw device precision.
