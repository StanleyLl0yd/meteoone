# M4 Verification Engine exit review

Status: **PRODUCTION-COMPOSITION FOLLOW-UP IN REVIEW**. The #209 exit review exposed one final production wiring gap; M4 closure is now conditional on exact-head checks for #211, squash-merge, and successful exact-`main` CI/security verification.

## Scope reviewed

M4 adds local verification capabilities over the completed M1 forecast core, M2 offline-first data layer and M3 product UI. The repository-wide exit review covered production Kotlin, Room schemas/migrations, verification transport/domain code, fusion integration, Gradle/module boundaries, tests, scripts, GitHub Actions, security/release policy, architecture documentation, and retained native-bundle verification gates.

The completed M4 slices are:

- #186 verification domain;
- #187 immutable forecast verification history;
- #194 exact model-run acquisition;
- #197 GHCNh station candidate discovery;
- #198 GHCNh retrieval and normalization;
- #199 Room v4 verification observations;
- #189 forecast/observation matching and sample production;
- #190 model-family skill aggregation;
- #191 guarded dynamic model-family weights;
- #192 guarded fusion integration and the initial exit review;
- #210 production evidence lifecycle/composition follow-up, implemented by #211.

## Evidence and privacy boundaries

- exact device location is never persisted by M4; forecast verification identity remains the canonical 0.1° `ForecastCoordinate`;
- verification forecast history stores integer tenths plus provider/model/run/lead provenance;
- station latitude/longitude is public observation-station metadata and observation tables contain no user-coordinate key;
- forecast and observation retention are bounded to 180 days;
- persisted lead time is re-derived and checked against model run/valid time on read;
- missing, invalid or conflicting evidence fails closed rather than being fabricated or silently rewritten.

## Observation acquisition and matching

- production GHCNh traffic is HTTPS-only to the exact NCEI host/path, with bounded 8 MiB station-list and 32 MiB station/year responses;
- the common transport disables redirects, cookies and automatic retry and enforces response-size/content-length invariants;
- station candidates are ranked only from the privacy-reduced forecast coordinate, with the production 75 km distance and 300 m known-elevation bounds;
- QC-invalid measurements stay out of canonical truth; equal-quality conflicts become missing;
- trace precipitation is preserved as source evidence but never fabricated as 0 mm;
- instantaneous matching is bounded to 30 minutes with deterministic earlier-observation ties;
- precipitation requires one exactly compatible interval; no partial accumulation, interpolation or synthetic hourly truth is introduced.

## Skill and dynamic-weight safety

- provider pipeline and model family remain separate identities;
- duplicate delivery paths collapse to one independent model-family sample before skill aggregation while provider diagnostics remain available;
- exact-coordinate skill is attempted before deterministic 5°×5° regional skill;
- default activation requires 120 independent samples and 14 model runs per family; regional evidence additionally requires three privacy-reduced coordinates;
- each chronological half requires 40 samples and five runs per family;
- evidence older than 30 days is stale;
- the same unique winner must be materially better by at least 5% in the full window and both chronological halves;
- scalar/precipitation weights use MAE and wind uses mean vector error;
- measured weights are deterministic, mean-normalized and capped at a 1.5× strongest/weakest ratio;
- all missing, sparse, stale, geographically narrow, immaterial or unstable evidence returns equal weights.

## Fusion integration

- `:forecast:domain` exposes only a verification-agnostic model-family weight-provider boundary;
- provider paths collapse before model-family weighting and duplicate providers never create duplicate votes;
- the legacy equal baseline remains unchanged;
- measured values are derived only from delivery paths that themselves expose trustworthy exact model-run provenance;
- a null-run Open-Meteo delivery is never assigned or inferred to have a direct-official run;
- at least two known model families must expose measured candidates for the same exact run before the guarded policy is queried;
- families without exact-run provenance remain in final fusion with their legacy collapsed value and neutral weight 1.0, outside the measured-policy request;
- temperature and sea-level pressure use guarded scalar weighting;
- precipitation first preserves exact interval selection and then weights only the selected interval;
- wind measured candidates use complete meteorological vectors from individually usable exact-run paths and collapse duplicate exact paths component-wise; non-calm missing direction is never borrowed from another path;
- wind gust and unsupported fields remain on the equal baseline;
- malformed decisions, evidence-provider failures or conflicting provenance fail back to equal fusion.

The initial #209 exit review found that `ForecastRepository.android()` still instantiated the equal-only engine, so the already-implemented M4 acquisition, persistence, matching and guarded-weight capabilities were not active in the real app refresh path. #210/#211 close that gap at the repository I/O composition boundary without moving observation/history I/O into synchronous fusion.

Production refresh now reuses ranked persisted GHCNh stations, works from a 30-day sample window, targets 14 complete exact 00Z runs, acquires at most two missing eligible runs per refresh while bootstrapping, refreshes observation evidence older than seven days when new runs are acquired, produces refresh-scoped samples, and clears those samples after the delegate refresh. Future-dated stored observations are bounded out before freshness decisions. Any non-cancellation verification failure becomes empty evidence/equal fallback; cancellation propagates. No backend, scheduler, provider-health or other M5 orchestration is introduced.

## Module and source-of-truth review

- `:verification:domain` remains a pure JVM module depending only on `:core:model`;
- `:verification:data` owns bounded observation transport/normalization and depends on `:core:network`, not Room or forecast execution;
- `:core:database` persists verification-domain types without depending on verification transport or forecast execution;
- `:forecast:data` consumes only the verification-domain/sample-source contract and does not depend on Room or `:verification:data`;
- the app continues to depend on `:forecast:repository`, not verification modules directly;
- the forecast repository public facade exposes no verification implementation types;
- Room remains the product forecast source of truth; M4 evidence is verification-only and is not a second UI weather source.

## Repository-wide security and CI review

- no `pull_request_target`, broad `write-all`, or `persist-credentials: true` workflow path was found;
- GitHub Actions are commit-SHA pinned;
- ordinary CI includes repository policy tests, verification domain/data tests, Room tests/migrations/lint, forecast domain/data/repository tests/lint/assembly, app tests/lint/build, committed Room-schema verification, native AAR verification and JNI/R8 boundary verification;
- Security and Quality, Dependency Review, Secret Scan, and compatibility-gated CodeQL remain unchanged;
- native ecCodes/libaec assets continue to be checked by the existing manifest/hash/AAR gates rather than being modified by M4.

## Intentional non-goals / deferred work

- no calibrated confidence percentage;
- no machine learning;
- no backend, central provider gateway/cache, provider-health system or server-side verification pipeline (M5);
- no M6 beta hardening work;
- no release/store publication or RuStore automation.

## Exit gate

M4 is complete only when #211 is merged from an exact green head and the resulting exact `main` SHA has successful CI, Security and Quality, and Secret Scan evidence, with CodeQL following the repository compatibility gate. #210 must close completed from that merge, and parent #185 must remain open until the post-merge exact-`main` evidence is confirmed.
