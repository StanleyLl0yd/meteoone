# Persisted Forecast Target Boundary

M2 needs one small piece of durable state in addition to Room forecast snapshots: the active privacy-reduced forecast target. Without it, a cold-started offline process cannot know which cached Room snapshot to reopen.

## Canonical target

`ForecastTarget` is a `:core:model` value containing:

- `ForecastCoordinate`;
- optional elevation metadata;
- a non-blank time-zone id.

The coordinate has already crossed the location privacy boundary and therefore uses the canonical 0.1 degree grid. Raw `android.location.Location` values and arbitrary raw coordinate doubles do not cross this persistence API.

## DataStore ownership

`:core:preferences` owns the target preference store. It depends on `:core:model` and AndroidX Preferences DataStore only; it must not depend on Room, network, location acquisition, provider execution, or forecast-domain implementation modules.

Its public surface is deliberately small:

- `ForecastTargetStore.target: Flow<ForecastTarget?>`;
- `ForecastTargetStore.set(target)`;
- `ForecastTargetStore.clear()`.

DataStore keys and preference encoding remain implementation details.

## Privacy-safe encoding

Latitude and longitude are persisted as signed integer tenths of a degree, matching the Room cache identity. For example:

```text
59.9, 30.3 -> 599, 303
```

This prevents exact/raw device precision from being persisted accidentally through the target store.

Target replacement and clearing use one DataStore edit, so consumers never observe a deliberately written partial target. A missing key, invalid coordinate, blank time-zone value, or wrong preference type decodes to no active target rather than fabricating state.

## Corruption and process restart

The production DataStore is process-singleton through the Android `preferencesDataStore` delegate. Physical file corruption is handled by replacing the corrupted Preferences payload with empty preferences, which yields no active target.

Tests exercise a real Preferences DataStore file, including close-and-reopen persistence and corrupt-file recovery. This is the durable identifier needed for the later app composition slice to reopen the matching Room snapshot without network access.

## Scope

This boundary does not perform location acquisition, refresh scheduling, retry/backoff, provider pacing, or UI work. Persisting general user settings can reuse DataStore later, but forecast payloads remain exclusively in Room.
