# MeteoOne Brand Guide

Status: Accepted

This document defines the durable product-brand direction for MeteoOne. It is the source of truth for brand positioning, visual language, tone of voice, and public-facing identity.

Implementation-level UI rules live in `docs/design/DESIGN_SYSTEM.md`.

The canonical app-icon source and its byte-preservation rules live in `docs/branding/APP_ICON.md`. This guide does not replace or rewrite that source artwork.

## Brand core

### Name

**MeteoOne**

The name should be written exactly as `MeteoOne` in product UI, store listings, documentation, and marketing copy unless a platform imposes a different technical form.

### Primary tagline

**Many models. One forecast.**

### Russian tagline

**Несколько моделей. Один прогноз.**

The tagline expresses the core product promise: multiple meteorological signals are combined into one understandable local forecast.

## Brand promise

MeteoOne turns the complexity of multiple weather models and providers into one clear forecast while preserving transparency for users who want to inspect the underlying evidence.

The product should communicate:

- one clear forecast first;
- serious meteorological technology underneath;
- transparent model/source comparison on demand;
- uncertainty without fake precision;
- calm, readable decisions rather than raw data overload.

The product must never imply that every provider response is independent meteorological evidence. Model-family and provider identity remain separate throughout the product.

## Positioning

MeteoOne sits between two extremes:

- consumer weather apps that hide the source and oversimplify uncertainty;
- professional meteorological tools that expose too much operational complexity for everyday use.

MeteoOne should feel professional without feeling institutional, and simple without feeling simplistic.

A concise positioning statement:

> Multiple weather models, one clear local forecast.

## Brand personality

The MeteoOne personality is:

- **Clear** — important weather information is immediately understandable.
- **Calm** — uncertainty and bad weather are communicated without visual panic.
- **Intelligent** — the product visibly respects data provenance and model disagreement.
- **Bright** — the visual language uses light, atmosphere, color, and depth.
- **Trustworthy** — the app avoids unsupported precision, exaggerated claims, and hidden uncertainty.
- **Local** — the forecast is always framed around the place and time that matter to the user.

## Visual direction

The visual direction is named:

**Modern Atmospheric Precision**

It combines:

- soft atmospheric depth;
- clear geometry;
- bright but controlled color;
- generous space;
- strong information hierarchy;
- restrained motion;
- precise data visualization.

MeteoOne must not look like:

- a cartoon weather app;
- a dense meteorological workstation;
- a GIS dashboard;
- a generic Material template with weather icons added;
- a clone of another major weather product.

The goal is recognizable identity without decorative clutter.

## Core visual metaphor

The primary MeteoOne metaphor is:

**many signals converging into one local forecast**

The canonical icon expresses this through several colored streams that converge on a single weather/location focal point.

The streams can represent:

- independent model families;
- provider pipelines;
- forecast signals;
- the fusion process.

The focal point represents:

- the user's forecast location;
- the resulting MeteoOne forecast;
- weather as the final human-facing outcome.

A short internal expression of the metaphor is:

**Many signals → one place → one forecast.**

This metaphor may inform onboarding, model comparison, loading/refresh motion, store graphics, and empty-state illustrations.

Do not copy the icon artwork into arbitrary UI components. Use the metaphor, not literal repetition.

## Brand principle: complexity inside, simplicity outside

MeteoOne may contain complex model provenance, benchmark evidence, fusion logic, caching, and provider fallbacks internally.

The default user experience should answer the practical questions first:

- What is the weather now?
- What will happen over the next few hours?
- Do I need an umbrella or warmer clothing?
- How strong is the wind?
- How stable is the forecast?

Technical detail remains available on demand.

This leads to the primary UX rule:

**Forecast first. Meteorology second.**

## Brand colors

The approved UI brand palette is derived from the visual language of the canonical artwork.

These are design tokens, not a claim that each value is an exact sampled pixel from the canonical PNG.

| Token | Hex | Role |
| --- | --- | --- |
| Meteo Blue | `#035BE1` | primary brand / fusion |
| Sky Blue | `#10A1F9` | secondary atmospheric accent |
| Cyan | `#24D0F6` | bright data / sky accent |
| Aqua | `#55E0F5` | soft atmospheric highlight |
| Deep Blue | `#003BC6` | deep gradient / dark atmospheric anchor |
| Sun Yellow | `#FCE15A` | warm weather accent |
| Sun Orange | `#FDA339` | warm secondary accent / observations |
| Fusion Violet | `#656AF5` | comparison / atmospheric accent |
| Soft Violet | `#9C97F9` | secondary comparison accent |
| Cloud White | `#FAFBFC` | bright neutral |

Bright cyan, yellow, and orange are primarily accent/data colors. They must not be used for small body text on light backgrounds when contrast is insufficient.

Exact semantic UI usage is defined in the design system.

## Model identity colors

Model-family colors remain stable throughout the product.

| Evidence | Color | Primary visual role |
| --- | --- | --- |
| MeteoOne fusion | `#035BE1` | dominant fused result |
| ECMWF IFS | `#24D0F6` | model comparison |
| DWD ICON | `#2ED9C3` | model comparison |
| NOAA GFS | `#8A74F6` | model comparison |
| Observation / measured fact | `#FDA339` | verification/reference data |

Color alone must never be the only way to identify a model. Labels, markers, line weight, or another accessible differentiator are required.

Provider identity is not assigned a competing set of permanent brand colors. Provider is secondary provenance; model family is the primary comparison identity.

## Gradients

Gradients are part of the MeteoOne atmospheric language, but they are reserved for large expressive surfaces.

Approved starting points:

- **Clear day:** `#035BE1 → #24D0F6`
- **Sunset:** `#656AF5 → #FDA339`
- **Night:** `#071426 → #003BC6`

Appropriate uses:

- current-weather hero;
- onboarding;
- splash/store artwork;
- large weather illustrations;
- exceptional full-width atmospheric surfaces.

Avoid gradients on:

- every card;
- standard buttons;
- small chips;
- dense tables;
- body-text backgrounds.

The interface should still work and remain legible without decorative gradients.

## Illustration style

Weather illustration style:

**soft atmospheric geometry**

Characteristics:

- rounded forms;
- soft glow;
- selective translucency;
- controlled blur;
- clear silhouette;
- limited layered gradients;
- no heavy black outlines;
- no photorealism;
- no childish cartoon styling.

Suitable subjects include:

- sun;
- clouds;
- rain;
- snow;
- fog;
- wind;
- thunder;
- atmospheric layers.

Illustrations should support information, not compete with it.

Generic stock weather photography is not part of the core MeteoOne visual system.

## Secondary brand motif: fusion streams

A simplified convergence/stream motif may appear as a secondary graphic element.

Possible uses:

- onboarding transition;
- refresh animation;
- model-comparison header;
- store screenshots;
- section dividers in brand artwork.

The motif should remain light and abstract.

Do not create a second competing logo from it unless the project owner explicitly approves one.

## Typography

MeteoOne uses the Android/system typeface, normally Roboto on Android.

Reasons:

- excellent Russian and English support;
- platform familiarity;
- accessibility;
- no bundled font dependency;
- predictable rendering;
- low application overhead.

Brand identity should come from composition, space, color, motion, and data visualization rather than an exotic custom font.

Large weather numbers are a major visual element. Temperature and other primary values should be confident, spacious, and immediately scannable.

## Logo and icon

The product name `MeteoOne` is the primary wordmark.

The current app icon is separately governed by `docs/branding/APP_ICON.md`.

Rules:

- never recreate the canonical icon from this document;
- never vectorize it as an implementation shortcut;
- never recolor or restyle it to match a theme;
- never modify its composition;
- derive only platform-required raster assets under the icon policy.

If the project owner designates a new PNG as canonical, update `APP_ICON.md` first and treat that exact file as the authoritative source.

## Tone of voice

MeteoOne speaks in short, useful, calm sentences.

Preferred voice:

- concrete;
- plain;
- neutral;
- uncertainty-aware;
- non-alarmist;
- technically honest.

Prefer:

- **Облачно. Дождь возможен после 18:00.**
- **Модели хорошо согласуются.**
- **После пятницы прогноз менее устойчив.**
- **Доступны данные двух из трёх моделей.**
- **Прогноз сохранён и может быть показан без сети.**

Avoid:

- bureaucratic meteorological prose;
- marketing superlatives;
- unsupported claims of accuracy;
- fake numerical confidence;
- unexplained provider/model jargon on the primary forecast screen;
- anthropomorphic AI language.

Do not say:

- “точность 99%” without calibrated evidence;
- “самый точный прогноз” without defensible comparative evidence;
- “AI knows your weather”;
- “guaranteed rain” unless the wording is intentionally describing an observed event rather than a forecast.

## Uncertainty language

Before numeric confidence is calibrated, MeteoOne communicates model agreement qualitatively.

Approved concepts:

- High agreement / **Высокое согласие**
- Medium agreement / **Среднее согласие**
- Low agreement / **Низкое согласие**
- Insufficient evidence / **Недостаточно данных**

Natural-language UI may be softer:

- **Модели хорошо согласуются.**
- **Есть заметный разброс между моделями.**
- **Прогноз менее устойчив.**
- **Недостаточно независимых данных для оценки согласия.**

Do not convert these labels into arbitrary percentages.

## Privacy voice

Privacy should be explained in user language, not security-policy language.

Preferred onboarding message:

**Ваше местоположение остаётся вашим**

> MeteoOne использует координаты только для определения прогноза и не сохраняет точную геопозицию.

The implementation must continue to follow the stronger technical requirements in `SECURITY.md` and `docs/adr/0002-location-privacy.md`.

## Public messaging hierarchy

Use three levels of brand communication.

### Level 1 — identity

**MeteoOne**

### Level 2 — promise

**Many models. One forecast.**

**Несколько моделей. Один прогноз.**

### Level 3 — explanatory expression

**ECMWF + ICON + GFS → MeteoOne**

The third form is explanatory marketing shorthand. It does not imply fixed provider availability, fixed model weights, or that these are the only future model families.

## Store positioning

Preferred Russian product descriptor:

**MeteoOne — прогноз погоды**

Preferred Russian short description:

**Несколько погодных моделей. Один понятный прогноз.**

Preferred English product descriptor:

**MeteoOne — Multi-model Weather**

Preferred English short description:

**Multiple weather models. One clear forecast.**

Do not put unsupported “most accurate”, “AI-powered accuracy”, or similar claims in store metadata.

## Product hierarchy

The default product hierarchy is:

1. place and freshness;
2. current weather;
3. MeteoOne fused forecast;
4. hourly forecast;
5. short daily outlook;
6. precipitation and wind;
7. qualitative model agreement;
8. model/source details.

A user should not need to understand ECMWF, ICON, GFS, provider transport, or fusion internals to use the primary forecast.

## Model comparison identity

The Models area is a defining MeteoOne feature.

It should make the following distinction visible:

- **Model:** ECMWF IFS / DWD ICON / NOAA GFS
- **Provider:** Open-Meteo / direct official source / another delivery path

The fused MeteoOne result remains visually dominant.

A second provider carrying the same model family must not appear as a second independent model vote.

## Motion personality

Motion communicates data flow, refresh, and convergence.

Good motion:

- model streams converging into the fused forecast;
- graph lines revealing smoothly;
- subtle weather-layer movement;
- small refresh-state transitions.

Bad motion:

- continuously spinning sun;
- bouncing decorative clouds;
- motion that makes reading charts harder;
- long ornamental transitions;
- animation required to understand state.

Motion must respect platform accessibility/reduced-motion settings.

## Brand do / do not

### Do

- keep the forecast visually primary;
- use atmospheric color with restraint;
- preserve generous spacing;
- keep data provenance available;
- show uncertainty honestly;
- use model colors consistently;
- make the MeteoOne fused result visually dominant;
- preserve light and dark theme quality;
- keep Russian and English equally first-class.

### Do not

- turn every surface into a gradient;
- overload the home screen with meteorological diagnostics;
- use color alone to encode model identity;
- show false numerical confidence;
- hide meaningful provider failure behind fabricated values;
- use excessive glassmorphism, glow, or blur;
- mimic another weather application's layout or illustrations;
- modify the canonical icon to fit a UI theme.

## Design review test

A proposed MeteoOne screen should pass these questions:

1. Can a normal user understand the weather in a few seconds?
2. Is the fused forecast visually clearer than the underlying model detail?
3. Is uncertainty communicated without fake precision?
4. Is the interface calm rather than dense?
5. Does the screen still work without decorative effects?
6. Are model and provider identities kept conceptually separate?
7. Can important state be understood without relying only on color?
8. Does it feel recognizably related to the MeteoOne icon and visual language?
9. Does it work in both Russian and English?
10. Does it preserve location privacy and graceful degradation?

If several answers are “no”, the design is not yet aligned with the MeteoOne brand.
