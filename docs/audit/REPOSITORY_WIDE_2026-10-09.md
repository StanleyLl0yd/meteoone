# Repository-wide audit checkpoint, 2026-10-09

## Scope and provenance

This audit is anchored to the historical Android-only `main`
`72efc17fdca1a4f6035e785b928f79f6dade07a9` before the reviewed fixes.
The entire immutable tracked tree was archived from an exact-SHA GitHub Actions checkout:
[run 37975533468](https://github.com/StanleyLl0yd/meteoone/actions/runs/37975533468),
one-day source artifact `11638815498`. The export contained **343 tracked
files** in **14 Gradle modules**, including **83 production Kotlin files** and
**67 Kotlin test files**. Every tracked path was inventoried; automated checks
covered repository policy/configuration, and focused manual code review covered
the critical production boundaries. This is **not** a claim that each of the
343 files received an independent line-by-line formal verification.

The product invariant is a standalone Android app without proprietary backend.
M6 and all later milestones are **out of scope**. The signed published test
release `v0.3.0-alpha.1` is **not** modified or rebuilt by this audit.

## Reviewed surfaces

| Surface | Review / evidence | Disposition |
| --- | --- | --- |
| App UI, navigation, resource localization and permissions | Main Compose entry, Forecast/Models/Settings, manifest, RU/EN localization parity and app-icon verification | No justified broad UI rewrite; functional device checklist 1-11 owner-reported pass, long-run scenario 12 pending |
| Privacy / durable target | Foreground coarse-location client, canonical 0.1-degree coordinate normalization, DataStore and Room boundaries, automated privacy verifier | Keep exact location transient; no new data or permissions |
| Offline cache and Room | Snapshot DAO's atomic transaction, stores/mappers, Room version 4, historical schemas 1-4 and migration registration, stale/fresh transitions | Do not change persisted schema or remove compatibility paths without migration proof |
| Weather execution | GFS/IFS/ICON and Open-Meteo planning/normalization, HTTPS status/range validation, host pacing, bounded retries, canonical 72-hour fusion | Preserve model/provider identity, strict metadata and partial-failure fallback |
| Verification M4 | Historical runs, GHCNh station/observation acquisition, parameter metrics, matching and guarded model weighting | Sparse-observation matching fixed and verified in merged [PR #292](https://github.com/StanleyLl0yd/meteoone/pull/292) |
| GRIB / JNI / native | Native C/JNI framing/bounded allocation, ecCodes definitions digest/extraction, DWD bzip2 limits and arm64 binaries | No speculative native rewrite or removal of reflected entry points; four ELF shared libraries have `PT_LOAD` alignment `0x4000` |
| CI, code quality, supply chain and release | All GitHub Actions paths, pinned Action policy, repository test runners, Qodana zero-finding evidence on earlier exact `main`, signed release rules | No relaxation of secret/signing/quality gates; remove stale reference to canceled server from one GRIB geometry comment |
| Dependencies / toolchain | Version catalog, wrapper, Gradle/JDK/Android CI contracts | [PR #297](https://github.com/StanleyLl0yd/meteoone/pull/297) merged Gradle 9.8.1 and Kotlin 2.4.21 after successful exact-head CI and security checks; exact-main post-merge validation is separate |

## Local verification completed

- Repository policy tests: **77 passed**.
- Forecast benchmark tests: **75 passed**.
- GRIB capability tests: **21 passed**.
- GRIB decoder candidate tests: **2 passed**.
- Total independent Python unit tests: **175 passed**.
- Explicit scripts: CI supply-chain policy, exact-coordinate privacy boundary,
  release metadata (`0.3.0-alpha.1`, code 3), release secret policy,
  native GRIB bundle integrity, RU/EN localization parity and app-icon integrity:
  **passed**.
- All tracked shell scripts passed `bash -n`.
- `:core:model` and `:verification:domain` production sources compiled with
  an available standalone Kotlin compiler; a focused sparse-observation
  regression scenario passed in that locally compiled domain.
- Existing bundled `arm64-v8a` libraries `libaec.so`, `libsz.so`,
  `libeccodes.so`, `libmeteoone_grib_jni.so` show `PT_LOAD` alignment
  `0x4000` with `readelf -lW`.
- This isolated audit container lacks the project's pinned Gradle distribution
  and network access, so local `./gradlew` cannot bootstrap. The full Android
  build, tests, lint and security checks must therefore be judged from the
  completed **exact-head GitHub Actions runs** for each PR, not implied by
  the standalone Kotlin or Python checks. Checks pending do not count as passes.
- `verify_release_version_history.py` requires genuine Git release history;
  synthetic snapshot commits are not suitable evidence for that historical gate.

## Findings and conservative dispositions

_Status updates below are based on independently verified GitHub evidence through 2026-10-13; the original 2026-10-09 source inventory and local checks remain historical evidence rather than a new repository-wide re-audit._

### A1 - M4 verification loses valid sparse-field matches

The old nearest-surface algorithm selected one timestamp before inspecting the
requested weather field. For example, an exact-time observation containing only
pressure masked a temperature reading 15 minutes earlier; an exact-time wind
reading missing direction masked a nearby usable wind vector. This affects
sample availability and potentially the gated verification weights.

[PR #292](https://github.com/StanleyLl0yd/meteoone/pull/292)
uses the nearest **usable** observation separately for temperature, pressure
and vector-compatible wind. It preserves the hard 30-minute tolerance,
deterministic earlier tie-break, calm-wind behavior and exact precipitation
interval matching. Two regression cases and matching documentation are included.
**Status: fixed and merged** as [PR #292](https://github.com/StanleyLl0yd/meteoone/pull/292), main commit `f8a80d77f302835a4ef64aa7113200f7d702cb3d`. Exact PR-head Android CI, CodeQL and security checks succeeded, as did full pinned Qodana [run 37977878975](https://github.com/StanleyLl0yd/meteoone/actions/runs/37977878975) with zero new problems.

### A2 - Obsolete server wording in the official GRIB model

`GribPointSelection.kt` described Android DWD geometry as shared with a
server runtime that was explicitly canceled and removed. Correct that
comment without changing executable code.

### A3 - Cached Current Conditions show the first historical hour

`ForecastSnapshot` used the first chronological forecast point for the
**Current Conditions** card even when Room reopened a previously saved
72-hour snapshot the next day. The freshness status was visible separately,
but the selected hour could be historic rather than representative of now.
This is tracked in
[#294](https://github.com/StanleyLl0yd/meteoone/issues/294) with a
time-window selector, hourly Compose-state progression and four boundary
tests in [PR #295](https://github.com/StanleyLl0yd/meteoone/pull/295).
The Room cache remains authoritative and the existing expired/partial
forecast display is not removed.
**Status: fixed and merged** via [PR #295](https://github.com/StanleyLl0yd/meteoone/pull/295) as commit `9b89ff5463a4ffc1d1a731e2a1c34445a4603bf9`. Exact-head and exact-main Android CI, CodeQL, Semgrep and Secret Scan succeeded.

### A4 - Post-audit verification and remaining device gate

- [#264](https://github.com/StanleyLl0yd/meteoone/issues/264):
  **completed 2026-10-13**. The actual canonical scheduled [Security and Quality run 38249200042](https://github.com/StanleyLl0yd/meteoone/actions/runs/38249200042), `event=schedule`, scanned exact `main` `9b89ff5463a4ffc1d1a731e2a1c34445a4603bf9` on 2026-10-12. Both Qodana and Semgrep succeeded. The Qodana job log states `Analysis results: 0 problem detected`; artifact `11747962000` was uploaded. `failThreshold: 0` and SHA pins were retained. An advisory CLI/linter compatibility warning did not prevent a completed clean scan.
- [#288](https://github.com/StanleyLl0yd/meteoone/issues/288):
  **open**. Owner-reported physical-device smoke cases 1-11 passed. Scenario 12
  (1-2 days of observation) and separately instrumented M4/JNI evidence remain open; UI smoke results do not prove those internal paths.
- [PR #297](https://github.com/StanleyLl0yd/meteoone/pull/297):
  **merged 2026-10-13** as `2bd72c87630da66275e776ce547e2035ad827979`; Gradle 9.8.1 and Kotlin 2.4.21 replace the obsolete unmerged Gradle 9.8.0 [PR #261](https://github.com/StanleyLl0yd/meteoone/pull/261). Exact-head CI and security checks passed; monitor new exact-main post-merge verification separately.
- [PR #298](https://github.com/StanleyLl0yd/meteoone/pull/298):
  SHA-pinned GitHub Actions dependency updates are reviewed and validated independently from this audit; do not conflate successful checks on an older base with checks on a rebased head.

## Review guardrails

Do not remove runtime contracts merely because a single-module Qodana inspection
reports unused symbols. Cross-module consumers exist for GHCNh factories,
JNI production adapter, HTTP headers, Open-Meteo request pacing, forecast
cadence and grid geometry. Preserve normalized-coordinate privacy,
Room migrations, native compatibility, model-family de-duplication, provenance,
offline fallback and deterministic equal-weight fallback until specific tests
justify a change. No backend, M6, new release, secret, signing key or location
data is introduced by this audit.
