# MeteoOne app icon

Status: Canonical

The project owner selected the current MeteoOne orbital weather-lens PNG artwork as the canonical application icon on 2026-09-09. It replaces the previously selected stream-based artwork.

## Canonical source

Repository path:

`docs/branding/assets/meteoone-icon-canonical-1254.png`

Properties:

- Format: PNG
- Dimensions: 1254 × 1254
- Color type: 8-bit RGB
- File size: 1,620,106 bytes
- SHA-256: `b9c404a92148203497f021de7fcd6ef473ce59ee28cb449c251a04f448bdb627`

The canonical PNG must be preserved byte-for-byte.

Do not overwrite, recompress, optimize, re-encode, reformat, vectorize, redraw, restyle, crop, pad, recolor, or otherwise rewrite the canonical source.

## Approved artwork concept

The icon uses the MeteoOne **Modern Atmospheric Precision** language:

- one luminous local weather/location core is visually dominant;
- independent colored model signals are represented as orbiting nodes and segmented arcs;
- the central weather lens represents the fused MeteoOne forecast;
- blue/cyan atmospheric depth, controlled turquoise/violet model accents, and a warm sun core remain consistent with the approved brand palette;
- the composition expresses **many signals → one place → one forecast** without using the previous stream/ribbon motif.

## Raster derivatives

Platform-required launcher/store assets may be generated only as raster derivatives of the canonical PNG while preserving the visible artwork unchanged.

Current approved derivatives:

| Purpose | Repository path | Size |
| --- | --- | ---: |
| Android mdpi | `app/src/main/res/mipmap-mdpi/ic_launcher.png` | 48 × 48 |
| Android hdpi | `app/src/main/res/mipmap-hdpi/ic_launcher.png` | 72 × 72 |
| Android xhdpi | `app/src/main/res/mipmap-xhdpi/ic_launcher.png` | 96 × 96 |
| Android xxhdpi | `app/src/main/res/mipmap-xxhdpi/ic_launcher.png` | 144 × 144 |
| Android xxxhdpi | `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` | 192 × 192 |
| Store/reference | `docs/branding/assets/meteoone-icon-store-512.png` | 512 × 512 |

The current derivatives were produced by square raster resampling only. No crop, padding, recolor, redraw, or composition change was applied.

Do not edit derivatives independently. Regenerate them from the canonical source when a platform-specific raster size is required.

## Exchange handoff

A transfer copy of the canonical source and the current raster derivatives is stored under:

`Google Drive / Exchange / MeteoOne Icon`

The repository canonical source remains the implementation source of truth after merge.

## Related brand documentation

- `docs/branding/BRAND_GUIDE.md` defines MeteoOne brand identity, visual metaphor, palette, and tone.
- `docs/design/DESIGN_SYSTEM.md` defines implementation-level UI tokens and component rules.

Those documents may describe the icon's visual language, but they do not replace this file and the canonical PNG above as the source of truth for icon bytes.

This branding decision follows the root `AGENTS.md` app-icon rules. A future replacement requires another explicit project-owner designation of a specific PNG.
