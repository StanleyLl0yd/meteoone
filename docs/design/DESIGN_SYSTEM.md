# MeteoOne Design System

Status: Accepted foundation

This document translates the MeteoOne brand into implementation-oriented UI rules.

Brand positioning and visual identity live in `docs/branding/BRAND_GUIDE.md`.

The system is intentionally small. New tokens and components should be added only when real product screens need them.

## Design principles

### Forecast first

The primary screen answers practical weather questions before exposing technical model details.

### One clear hierarchy

Each screen should have one visually dominant task or information group.

### Atmospheric, not decorative

Color, gradients, glow, and motion may create weather atmosphere, but they must never reduce legibility or compete with data.

### Explainable uncertainty

Use qualitative agreement and real model spread. Do not invent numeric confidence.

### Accessible by construction

Color is never the only state signal. Text scaling, contrast, touch targets, semantics, and reduced motion are baseline requirements.

### Graceful degradation is visible but calm

Cached data, missing providers, stale forecasts, and partial results are product states, not exceptional crashes.

## Foundation platform

Initial UI implementation target:

- Android;
- Jetpack Compose;
- Material 3 primitives where they fit;
- system/Roboto typography;
- light and dark themes;
- Russian and English localization;
- adaptive layouts.

Material 3 is a foundation, not the visual identity. MeteoOne brand tokens and component composition should remain recognizable.

## Color system

### Brand primitives

| Token | Hex |
| --- | --- |
| `brandBlue` | `#035BE1` |
| `skyBlue` | `#10A1F9` |
| `cyan` | `#24D0F6` |
| `aqua` | `#55E0F5` |
| `deepBlue` | `#003BC6` |
| `sunYellow` | `#FCE15A` |
| `sunOrange` | `#FDA339` |
| `fusionViolet` | `#656AF5` |
| `softViolet` | `#9C97F9` |
| `cloudWhite` | `#FAFBFC` |

### Light theme semantic tokens

| Token | Value | Use |
| --- | --- | --- |
| `background` | `#F7FAFF` | application background |
| `surface` | `#FFFFFF` | standard cards/sheets |
| `surfaceAlt` | `#EEF5FF` | subtle grouped surface |
| `primary` | `#035BE1` | primary action / fusion |
| `primaryContainer` | `#D9EAFF` | selected/soft primary surface |
| `textPrimary` | `#101828` | main text |
| `textSecondary` | `#667085` | supporting text |
| `outline` | `#D7E1EE` | low-emphasis boundaries |

### Dark theme semantic tokens

| Token | Value | Use |
| --- | --- | --- |
| `background` | `#071426` | application background |
| `surface` | `#0D2038` | standard cards/sheets |
| `surfaceAlt` | `#122B49` | elevated/grouped surface |
| `primary` | `#4DA3FF` | primary action / fusion |
| `primaryContainer` | `#123D70` | selected/soft primary surface |
| `textPrimary` | `#F5F8FC` | main text |
| `textSecondary` | `#B8C4D4` | supporting text |
| `outline` | `#2A4665` | low-emphasis boundaries |

### Color usage rules

- Never use `sunYellow`, `sunOrange`, or bright cyan as small body text on a light surface unless contrast has been verified.
- Use brand blue for the MeteoOne fused result and primary interaction, not for every decorative element.
- Use warm colors to highlight weather/observation concepts, not general navigation.
- Use Material semantic error colors for actual errors/critical states rather than repurposing model colors.
- Do not encode agreement or provider availability solely through hue.

## Model-family visualization

Stable model-family tokens:

| Evidence | Color | Marker recommendation |
| --- | --- | --- |
| MeteoOne fusion | `#035BE1` light / `#4DA3FF` dark | thick line, no repeated marker |
| ECMWF IFS | `#24D0F6` | circle |
| DWD ICON | `#2ED9C3` | square |
| NOAA GFS | `#8A74F6` | diamond |
| Observation | `#FDA339` | filled dot / vertical marker |

Rules:

- Keep these identities stable across charts and model cards.
- Always label the model in legends and accessible descriptions.
- MeteoOne fusion is visually dominant over source-model lines.
- Provider identity is shown as text/provenance rather than another permanent color system.
- Duplicate providers of one model family do not get independent model colors.

## Typography

Use the platform/system font; on Android this normally means Roboto.

Do not bundle a custom font unless a later design review establishes a clear benefit that justifies APK size, localization, rendering, and accessibility costs.

Recommended semantic hierarchy:

| Role | Material/approximate scale |
| --- | --- |
| Hero temperature | 56–72 sp, responsive |
| Display title | Display Small / 36 sp class |
| Screen title | Headline Medium / 28 sp class |
| Section title | Title Large / 22 sp class |
| Card title | Title Medium / 16 sp class |
| Body | Body Large / 16 sp class |
| Supporting | Body Medium / 14 sp class |
| Metadata | Label Medium / 12 sp class |

Rules:

- Use `sp`, never fixed pixel text.
- Support user font scaling without clipping primary weather values.
- Prefer short lines and clear hierarchy over reducing font size.
- Keep numbers aligned and visually stable where practical.
- Do not use all-caps for normal navigation or weather states.
- Degree symbols and units should remain visually subordinate to the numeric value where appropriate.

## Spacing

Base grid: **4 dp**

Approved spacing steps:

- 4 dp — micro gap;
- 8 dp — tight internal spacing;
- 12 dp — compact component spacing;
- 16 dp — standard internal/card spacing;
- 24 dp — section spacing;
- 32 dp — major group separation;
- 48 dp — large structural separation.

Default screen horizontal padding:

- phones: 16–24 dp depending on density/layout;
- larger adaptive layouts: increase based on content width, not device width alone.

Avoid arbitrary one-off spacing values unless needed for optical alignment.

## Shape

Recommended corner radii:

- 12 dp — compact controls;
- 16 dp — standard cards;
- 24 dp — major weather cards;
- 28–32 dp — hero/expressive surfaces.

Do not make every rectangular element a pill.

Pills are appropriate for:

- compact filters;
- selected view modes;
- short status chips.

## Touch targets

Interactive targets should be at least **48 × 48 dp** unless platform guidance explicitly permits a different accessible target.

Small visual icons may sit inside larger invisible/semantic hit areas.

## Elevation and boundaries

MeteoOne prefers:

1. spacing;
2. surface tone;
3. subtle outline;
4. elevation/shadow only when needed.

Avoid heavy drop shadows.

Large atmospheric hero surfaces may use soft glow or depth, but standard cards should remain quiet.

## Gradient system

Approved large-surface gradient families:

### Clear day

`#035BE1 → #24D0F6`

### Sunset

`#656AF5 → #FDA339`

### Night

`#071426 → #003BC6`

Gradients may vary in angle and stop positions for responsive composition, but the underlying palette should remain recognizable.

Never place low-contrast text directly on a gradient without a tested contrast strategy.

## Navigation

Initial bottom navigation contains exactly three primary destinations:

1. **Forecast / Прогноз**
2. **Models / Модели**
3. **Settings / Настройки**

Do not add a redundant “Home” destination.

About, attribution, privacy, and diagnostic preferences initially belong under Settings unless product scope later justifies a separate destination.

On larger layouts these destinations may adapt to navigation rail/pane patterns while preserving information architecture.

## Forecast screen

Recommended content order:

### 1. Location header

Show:

- place name;
- data freshness/update time;
- manual-location affordance when applicable.

Do not expose exact GPS coordinates.

### 2. Current-weather hero

Primary:

- temperature;
- condition;
- optional weather illustration.

Secondary:

- feels-like temperature;
- meaningful near-term condition.

The hero is the primary atmospheric surface and the most appropriate place for restrained gradients.

### 3. MeteoOne forecast status

Show the fused result as the main forecast.

When useful, expose qualitative agreement in natural language:

- **Models agree well**
- **Модели хорошо согласуются**
- **Forecast is less stable**
- **Прогноз менее устойчив**

Do not show an arbitrary confidence percentage.

### 4. Hourly forecast

Horizontal time rail or responsive equivalent.

Each hour should prioritize:

- time;
- condition;
- temperature.

Add precipitation/wind only when it remains readable.

A compact temperature line may connect the hourly values.

### 5. Short daily outlook

Prefer compact rows over oversized repeated cards.

Typical hierarchy:

- day;
- weather icon/condition;
- high / low;
- meaningful precipitation.

### 6. Precipitation and wind

Use focused cards/sections when these variables materially affect decisions.

### 7. Model agreement

A compact summary links to detailed model comparison.

### 8. Details

Pressure, humidity, dew point, visibility, provenance, and other advanced values should remain available without dominating the main forecast.

## Models screen

The Models screen is a defining MeteoOne feature.

### Top section

Show:

**MeteoOne fusion**

The fused line/result is visually dominant.

### Model comparison

Initial model families:

- ECMWF IFS;
- DWD ICON;
- NOAA GFS.

Recommended parameter selector:

- Temperature / Температура
- Precipitation / Осадки
- Wind / Ветер
- Pressure / Давление

Only show parameters with valid comparable provenance.

### Provenance

Each model may expose:

- model family;
- provider;
- model run;
- generated/updated time;
- availability.

Example:

**ECMWF IFS**  
Provider: Open-Meteo

Later:

**ECMWF IFS**  
Provider: ECMWF Open Data

Those are two provider paths to one model family, not two independent votes.

## Model-agreement component

The qualitative agreement component should include:

- label;
- brief explanation;
- simple spread visualization when useful;
- model values on expansion.

Good example:

**High agreement**  
ECMWF 12.1° · ICON 12.4° · GFS 11.8°  
Temperature spread: 0.6 °C

Do not assign green/yellow/red traffic-light meaning without text.

## Charts

### General rules

- time runs left to right;
- use stable model colors;
- MeteoOne fusion line is thicker;
- preserve raw point identity;
- show gaps for missing data instead of fabricating values;
- do not use 3D;
- do not add decorative grid density;
- keep axis labels sparse and readable;
- use localized units and time.

### Smoothing

If curves are visually smoothed, the interpolation must not overshoot or imply materially different forecast values.

Data points remain authoritative.

### Model accessibility

Use at least two identifying channels:

- color + label;
- color + marker;
- color + line treatment.

Color-only legends are insufficient.

### Observation overlays

Measured/reference observations use the observation token and must be explicitly labelled as observations.

Do not visually imply that forecast and observation series have identical semantics when they do not.

## Weather iconography

Preferred weather icon style:

- rounded;
- simple geometry;
- soft layer depth;
- limited gradient use;
- no heavy outlines;
- consistent optical size.

Core eventual set:

- clear;
- partly cloudy;
- cloudy;
- fog;
- rain;
- heavy rain;
- snow;
- sleet;
- thunderstorm;
- wind.

Weather icons must have accessible text labels where their meaning is otherwise unavailable.

Do not copy the canonical app-icon artwork into each weather icon.

## Cards

Standard card:

- 16 dp radius;
- quiet surface contrast;
- 16 dp internal padding;
- minimal/no shadow.

Major weather card:

- 24 dp radius;
- may use atmospheric gradient;
- 20–24 dp internal padding;
- stronger hierarchy.

Avoid nesting multiple elevated cards inside another elevated card.

## Buttons

Primary actions use the primary brand token when appropriate.

Secondary actions should prefer lower-emphasis Material patterns.

Do not make routine weather exploration look like a form full of call-to-action buttons.

Icon-only buttons require content descriptions.

## Chips and segmented controls

Use chips/segments for short, mutually understandable view switches such as:

- hourly/daily;
- temperature/precipitation/wind/pressure;
- model filters.

Avoid using chips as decorative metadata containers for every fact.

## Loading

Preferred behavior:

1. show usable cached forecast immediately when available;
2. refresh independently;
3. indicate refresh without blocking the screen.

Initial no-data loading may use a restrained skeleton or progress indicator.

Do not hide a valid cached forecast behind a full-screen spinner.

## Offline state

When cached data is available:

- keep the forecast visible;
- show freshness clearly;
- communicate offline status quietly.

Example:

**Offline · updated 42 min ago**

Do not present offline mode as fatal when valid cached data exists.

## Stale data

Data freshness and data availability are separate concepts.

Stale-but-usable data should remain visible with an explicit timestamp/state.

Do not silently style stale data as current.

## Partial provider failure

If one provider/model path fails but sufficient forecast evidence remains:

- continue showing the forecast;
- avoid disruptive error dialogs;
- expose reduced availability in model details/agreement state.

Example:

**2 of 3 model families available**

Do not fabricate the missing source.

## Total failure

When neither current nor cached data is usable:

- explain the state plainly;
- offer retry;
- offer manual location where relevant;
- preserve settings and context.

Error copy should say what the user can do, not expose raw HTTP/provider exceptions.

## Location-permission state

Location permission is not a prerequisite for the whole app.

If permission is denied:

- explain why location improves convenience;
- offer manual-location fallback;
- avoid coercive repeated prompts.

Exact coordinates must not be shown in normal UI or diagnostics unless a future reviewed feature explicitly requires it.

## Settings screen

Initial settings may group:

### Forecast

- units;
- update behavior where user-configurable;
- manual location entry/selection.

### Appearance

- system/light/dark;
- future accessibility display preferences if needed.

### Data and sources

- model/provider attribution;
- data freshness explanation.

### Privacy

- exact-location handling summary;
- permissions.

### About

- version;
- licenses/attributions;
- privacy/security links.

Avoid exposing implementation knobs that normal users cannot meaningfully evaluate.

## Motion

Motion should communicate state and data flow.

Recommended duration classes:

- 100–180 ms — small feedback;
- 180–300 ms — standard state transition;
- 300–450 ms — expressive atmospheric transition.

Use spring motion only where it remains calm and predictable.

Examples:

- model streams subtly converge during refresh;
- chart lines reveal on first display;
- hero atmosphere transitions when weather state changes.

Do not loop decorative motion indefinitely unless it is extremely subtle and power-conscious.

Respect reduced-motion/platform accessibility preferences.

## Haptics

Use haptics sparingly for direct interaction feedback, not ambient weather changes.

Do not vibrate for routine forecast refreshes.

Severe-weather alert haptics belong to a future alert-specific design/security/product decision.

## Accessibility

Required baseline:

- semantic labels for weather icons and icon-only controls;
- logical screen-reader order;
- 48 dp interaction targets;
- support for font scaling;
- sufficient contrast;
- no color-only state;
- reduced-motion compatibility;
- focus visibility;
- localized content descriptions;
- charts with textual summaries where practical.

A visually elegant chart that is inaccessible is not complete.

## Localization

Russian and English are first-class.

Rules:

- do not hardcode user-facing strings in Kotlin;
- allow Russian labels to be longer;
- avoid fixed-width text containers that only fit English;
- localize time/date/number formatting;
- keep model names unchanged where they are official names;
- localize explanatory copy around them;
- verify pluralization and unit formatting.

## Number and unit presentation

Avoid arbitrary precision.

Typical consumer display:

- temperature: whole degree by default where precision does not add value;
- wind: practical precision appropriate to units;
- pressure: practical hPa precision;
- precipitation: precision based on magnitude and source semantics.

Detailed model comparison may expose more precision when it is meaningful.

Never visually imply measurement precision that the source does not support.

## Freshness and timestamps

Use local user-facing time.

Examples:

- **Updated 16:42**
- **Обновлено в 16:42**
- **42 min ago** where relative time is clearer.

Model-run metadata may use explicit UTC internally/details, but primary UI should avoid forcing users to interpret UTC.

## Copy rules

Prefer:

- short sentence;
- one idea;
- direct consequence.

Good:

- **Rain possible after 18:00.**
- **Дождь возможен после 18:00.**
- **Models agree well.**
- **Модели хорошо согласуются.**
- **Forecast is less stable after Friday.**
- **После пятницы прогноз менее устойчив.**

Avoid:

- long formal prose;
- unexplained abbreviations;
- fake certainty;
- raw provider error strings.

## Privacy component language

Preferred explanatory block:

**Your location stays yours**

MeteoOne uses coordinates to resolve the forecast and does not persist your exact location.

Russian:

**Ваше местоположение остаётся вашим**

MeteoOne использует координаты только для определения прогноза и не сохраняет точную геопозицию.

Implementation requirements remain governed by the privacy ADR and security policy.

## Empty states

Empty states should be useful, not whimsical filler.

Examples:

- no manual location yet → explain how to add one;
- model unavailable → say unavailable and retain other models;
- no compatible precipitation provenance → omit/disable that comparison with explanation;
- no network and no cache → retry/manual location guidance.

## Decorative effects budget

Use blur, glow, transparency, and gradients primarily in:

- hero surface;
- onboarding;
- store artwork;
- limited large illustration.

Standard information surfaces should remain crisp.

If every component glows, nothing is visually important.

## Adaptive layout

Phone-first does not mean phone-only.

On wider windows:

- keep readable content widths;
- move navigation to rail/pane when appropriate;
- allow forecast + details/model comparison side-by-side;
- do not stretch cards edge-to-edge merely because space exists.

Preserve the same information hierarchy across form factors.

## System bars and edge-to-edge

Use edge-to-edge layouts with deliberate insets.

Atmospheric hero surfaces may extend visually behind system bars when contrast remains safe.

System-bar icon appearance must remain readable in both themes.

## Design-system ownership

Future `:core:designsystem` implementation should contain only real reusable primitives/tokens required by product screens.

Do not create an empty component framework in advance.

Likely eventual responsibilities:

- MeteoOne color tokens;
- typography;
- shapes;
- core spacing conventions;
- shared weather/model visualization primitives;
- common branded surfaces.

Feature-specific UI remains in feature modules unless genuine reuse emerges.

## Review checklist

Before merging a new UI component or screen, verify:

- [ ] Forecast/user task remains primary.
- [ ] Uses design-system colors rather than ad-hoc colors.
- [ ] Works in light and dark themes.
- [ ] Works in Russian and English.
- [ ] Supports font scaling.
- [ ] Important controls meet touch-target guidance.
- [ ] State is not communicated through color alone.
- [ ] Missing/stale/partial data is explicit rather than fabricated.
- [ ] Model and provider are not conflated.
- [ ] No fake numeric confidence is introduced.
- [ ] Motion is optional and restrained.
- [ ] Exact location is not persisted or exposed.
- [ ] Decorative effects do not reduce readability.
- [ ] MeteoOne fusion remains visually dominant in model comparison.
