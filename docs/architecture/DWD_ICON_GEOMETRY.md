# DWD ICON grid geometry lifecycle

Status: M1 production boundary.

DWD ICON forecast fields use the native icosahedral grid (GRIB2 GDT 101). MeteoOne therefore resolves a normalized forecast coordinate against the official time-invariant `CLAT` and `CLON` fields before selecting a point from DWD forecast fields.

## Ownership and provenance

`DwdIconGridGeometryPlanner` is the authority for the two official geometry requests. Both request URIs are derived from one validated operational ICON `modelRun` (00/06/12/18 UTC). Geometry ownership is attached to that plan's `modelRun`; MeteoOne does not infer run ownership from the time metadata inside the time-invariant CLAT/CLON GRIB messages.

The production loader uses the same bounded M1 transport, DWD bzip2 decompressor and ecCodes JNI session as direct forecast decoding. It validates one GRIB2 message per coordinate field, the native boundary layout, bounded value cardinality, CLAT/CLON signatures (`discipline/category/number` `0/191/1` and `0/191/2`), instantaneous PDT 0, GDT 101, finite geographic coordinates and exact CLAT/CLON cardinality equality. Longitudes are normalized to MeteoOne's `[0, 360)` degrees-east representation only after their source range is validated.

## In-memory lifecycle

Geometry is deliberately transient M1 state:

- exactly one geometry slot is retained in process memory;
- the slot is reusable only when `geometry.modelRun == requestedPlan.modelRun`;
- a different run fetches and validates both CLAT and CLON again;
- replacement is atomic: a new geometry is published only after both fields validate and `DwdIconGridGeometry` is constructed successfully;
- a failed load cannot publish a half-decoded geometry or relabel an older run;
- DWD forecast-field selection independently requires the geometry run to match both the forecast request plan and its geometry plan.

There is no disk, Room, DataStore or other persistent geometry cache in M1. Persistent forecast/cache policy belongs to a later milestone and must not be introduced through this boundary.

## Native containment

Full CLAT/CLON arrays, ecCodes/JNI representations and nearest-cell selection remain inside `:forecast:data`. Other modules receive only MeteoOne-owned canonical point forecast values. Raw device coordinates never enter this boundary; selection uses the privacy-normalized `ForecastCoordinate` already carried by the validated provider decode context.
