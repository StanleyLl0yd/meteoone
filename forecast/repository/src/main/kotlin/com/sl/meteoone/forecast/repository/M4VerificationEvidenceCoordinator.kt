package com.sl.meteoone.forecast.repository

import com.sl.meteoone.core.database.DefaultVerificationSampleProducer
import com.sl.meteoone.core.database.ForecastVerificationHistoryStore
import com.sl.meteoone.core.database.StoredVerificationForecastRun
import com.sl.meteoone.core.database.StoredVerificationObservationSeries
import com.sl.meteoone.core.database.VerificationObservationStore
import com.sl.meteoone.core.database.VerificationSampleProducer
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.data.execution.ExactRunAcquisitionResult
import com.sl.meteoone.forecast.data.execution.ExactRunForecastAcquirer
import com.sl.meteoone.forecast.data.execution.VerificationWeightSampleSource
import com.sl.meteoone.verification.data.ghcnh.GHCNH_SOURCE_ID
import com.sl.meteoone.verification.data.ghcnh.GhcnhObservationSource
import com.sl.meteoone.verification.data.ghcnh.GhcnhObservationsResult
import com.sl.meteoone.verification.data.ghcnh.GhcnhStationCandidateRanker
import com.sl.meteoone.verification.data.ghcnh.GhcnhStationCandidateSource
import com.sl.meteoone.verification.data.ghcnh.GhcnhStationCandidatesResult
import com.sl.meteoone.verification.domain.VerificationSample
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException

private const val M4_TARGET_COMPLETE_RUNS = 14
private const val M4_MAX_RUNS_PER_REFRESH = 2
private const val M4_RUN_LOOKBACK_DAYS = 30L
private val M4_SAMPLE_WINDOW: Duration = Duration.ofDays(M4_RUN_LOOKBACK_DAYS)
private val M4_OBSERVATION_REFRESH_AGE: Duration = Duration.ofDays(7)
private val M4_PUBLICATION_GUARD: Duration = Duration.ofHours(7)
private val M4_MODEL_FAMILIES = setOf(
    ModelFamily.ECMWF_IFS,
    ModelFamily.DWD_ICON,
    ModelFamily.NOAA_GFS,
)

internal fun interface M4ExactRunSource {
    fun acquire(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        timeZoneId: String,
        modelRuns: List<Instant>,
        capturedAt: Instant,
    ): ExactRunAcquisitionResult
}

internal fun interface M4StationCandidateSource {
    fun candidates(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
    ): GhcnhStationCandidatesResult
}

internal fun interface M4ObservationSource {
    fun observations(
        candidates: List<com.sl.meteoone.verification.data.ghcnh.GhcnhStationCandidate>,
        startInclusive: Instant,
        endExclusive: Instant,
    ): GhcnhObservationsResult
}

internal fun interface M4VerificationEvidenceSource {
    suspend fun prepareSamples(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        timeZoneId: String,
        evaluatedAt: Instant,
    ): List<VerificationSample>
}

internal class M4VerificationEvidenceCoordinator(
    private val historyStore: ForecastVerificationHistoryStore,
    private val observationStore: VerificationObservationStore,
    private val exactRunSource: M4ExactRunSource,
    private val stationCandidateSource: M4StationCandidateSource,
    private val observationSource: M4ObservationSource,
    private val sampleProducer: VerificationSampleProducer = DefaultVerificationSampleProducer(),
    private val storedStationRanker: GhcnhStationCandidateRanker = GhcnhStationCandidateRanker(),
) : M4VerificationEvidenceSource {
    override suspend fun prepareSamples(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        timeZoneId: String,
        evaluatedAt: Instant,
    ): List<VerificationSample> {
        val fromInclusive = evaluatedAt.minus(M4_SAMPLE_WINDOW)
        var history = historyStore.readSince(coordinate, fromInclusive)
        val runsToAcquire = planRunsToAcquire(history, evaluatedAt)
        val attemptedRunAcquisition = runsToAcquire.isNotEmpty()

        if (attemptedRunAcquisition) {
            val acquired = exactRunSource.acquire(
                coordinate = coordinate,
                elevationMeters = elevationMeters,
                timeZoneId = timeZoneId,
                modelRuns = runsToAcquire,
                capturedAt = evaluatedAt,
            )
            historyStore.archive(
                coordinate = coordinate,
                forecasts = acquired.forecasts,
            )
            history = historyStore.readSince(coordinate, fromInclusive)
        }

        var observations = readBestStoredSeries(
            coordinate = coordinate,
            elevationMeters = elevationMeters,
            fromInclusive = fromInclusive,
            endInclusive = evaluatedAt,
        )
        val shouldRefreshObservations =
            observations == null ||
                (
                    attemptedRunAcquisition &&
                        observations.latestEvidenceAt()
                            ?.isBefore(evaluatedAt.minus(M4_OBSERVATION_REFRESH_AGE)) != false
                )

        if (shouldRefreshObservations) {
            observations = refreshObservations(
                coordinate = coordinate,
                elevationMeters = elevationMeters,
                startInclusive = fromInclusive,
                endExclusive = evaluatedAt,
            ) ?: observations
        }

        val selectedObservations = observations ?: return emptyList()
        return history.flatMap { run ->
            sampleProducer.produce(
                forecast = run,
                observations = selectedObservations,
            )
        }
    }

    private suspend fun readBestStoredSeries(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        fromInclusive: Instant,
        endInclusive: Instant,
    ): StoredVerificationObservationSeries? {
        val stations = observationStore.readStations(GHCNH_SOURCE_ID)
            .filter { it.sourceId == GHCNH_SOURCE_ID }
        val ranked = storedStationRanker.rankStoredStations(
            target = coordinate,
            targetElevationMeters = elevationMeters,
            stations = stations,
        )
        ranked.forEach { station ->
            val series = observationStore.readSince(
                sourceId = station.sourceId,
                stationId = station.stationId,
                fromInclusive = fromInclusive,
            )?.boundedAt(endInclusive)
            if (series != null && series.hasUsableEvidence()) return series
        }
        return null
    }

    private suspend fun refreshObservations(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        startInclusive: Instant,
        endExclusive: Instant,
    ): StoredVerificationObservationSeries? {
        if (!startInclusive.isBefore(endExclusive)) return null
        val candidates = when (
            val result = stationCandidateSource.candidates(coordinate, elevationMeters)
        ) {
            is GhcnhStationCandidatesResult.Available -> result.candidates
            GhcnhStationCandidatesResult.Unavailable -> return null
        }
        val series = when (
            val result = observationSource.observations(
                candidates = candidates,
                startInclusive = startInclusive,
                endExclusive = endExclusive,
            )
        ) {
            is GhcnhObservationsResult.Available -> result.series
            GhcnhObservationsResult.Unavailable -> return null
        }

        observationStore.archive(
            station = series.station,
            surfaceObservations = series.surfaceObservations,
            precipitationObservations = series.precipitationObservations,
        )
        return StoredVerificationObservationSeries(
            station = series.station,
            surfaceObservations = series.surfaceObservations,
            precipitationObservations = series.precipitationObservations,
        )
    }
}

internal class RefreshScopedVerificationWeightSampleSource : VerificationWeightSampleSource {
    @Volatile
    private var current: List<VerificationSample> = emptyList()

    fun replace(samples: Collection<VerificationSample>) {
        current = samples.toList()
    }

    override fun samples(
        request: com.sl.meteoone.verification.domain.VerificationWeightRequest,
    ): Collection<VerificationSample> = current
}

internal class M4ForecastRefreshSource(
    private val coordinator: M4VerificationEvidenceSource,
    private val sampleSource: RefreshScopedVerificationWeightSampleSource,
    private val delegate: ForecastRefreshSource,
) : ForecastRefreshSource {
    override suspend fun forecast(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        timeZoneId: String,
        generatedAt: Instant,
    ): ForecastRefreshSourceResult {
        sampleSource.replace(emptyList())
        try {
            val samples = try {
                coordinator.prepareSamples(
                    coordinate = coordinate,
                    elevationMeters = elevationMeters,
                    timeZoneId = timeZoneId,
                    evaluatedAt = generatedAt,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            } catch (_: LinkageError) {
                emptyList()
            }
            sampleSource.replace(samples)
            return delegate.forecast(
                coordinate = coordinate,
                elevationMeters = elevationMeters,
                timeZoneId = timeZoneId,
                generatedAt = generatedAt,
            )
        } finally {
            sampleSource.replace(emptyList())
        }
    }
}

internal fun productionM4VerificationEvidenceCoordinator(
    historyStore: ForecastVerificationHistoryStore,
    observationStore: VerificationObservationStore,
): M4VerificationEvidenceCoordinator {
    val exactRuns = ExactRunForecastAcquirer.default()
    val stationCandidates = GhcnhStationCandidateSource.default()
    val observations = GhcnhObservationSource.default()
    return M4VerificationEvidenceCoordinator(
        historyStore = historyStore,
        observationStore = observationStore,
        exactRunSource = M4ExactRunSource { coordinate, elevation, timeZoneId, runs, capturedAt ->
            exactRuns.acquire(
                coordinate = coordinate,
                elevationMeters = elevation,
                timeZoneId = timeZoneId,
                modelRuns = runs,
                capturedAt = capturedAt,
            )
        },
        stationCandidateSource = M4StationCandidateSource { coordinate, elevation ->
            stationCandidates.candidates(
                target = coordinate,
                targetElevationMeters = elevation,
            )
        },
        observationSource = M4ObservationSource { candidates, start, end ->
            observations.observations(
                candidates = candidates,
                startInclusive = start,
                endExclusive = end,
            )
        },
    )
}

private fun planRunsToAcquire(
    history: List<StoredVerificationForecastRun>,
    evaluatedAt: Instant,
): List<Instant> {
    val completeRuns = history
        .groupBy(StoredVerificationForecastRun::modelRun)
        .filterValues { runs ->
            runs.map(StoredVerificationForecastRun::modelFamily).toSet()
                .containsAll(M4_MODEL_FAMILIES)
        }
        .keys

    val latestEligibleDate = evaluatedAt
        .minus(M4_PUBLICATION_GUARD)
        .atOffset(ZoneOffset.UTC)
        .toLocalDate()
    val recentRuns = (0L until M4_RUN_LOOKBACK_DAYS).map { dayOffset ->
        latestEligibleDate
            .minusDays(dayOffset)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
    }

    val selected = linkedSetOf<Instant>()
    val latest = recentRuns.first()
    if (latest !in completeRuns) selected += latest

    if (completeRuns.size < M4_TARGET_COMPLETE_RUNS) {
        recentRuns.forEach { run ->
            if (selected.size >= M4_MAX_RUNS_PER_REFRESH) return@forEach
            if (run !in completeRuns) selected += run
        }
    }
    return selected
        .take(M4_MAX_RUNS_PER_REFRESH)
        .sorted()
}

private fun StoredVerificationObservationSeries.boundedAt(
    endInclusive: Instant,
): StoredVerificationObservationSeries = copy(
    surfaceObservations = surfaceObservations.filter {
        !it.observedAt.isAfter(endInclusive)
    },
    precipitationObservations = precipitationObservations.filter {
        !it.interval.end.isAfter(endInclusive)
    },
)

private fun StoredVerificationObservationSeries.hasUsableEvidence(): Boolean =
    surfaceObservations.isNotEmpty() || precipitationObservations.isNotEmpty()

private fun StoredVerificationObservationSeries.latestEvidenceAt(): Instant? =
    buildList {
        surfaceObservations.maxOfOrNull { it.observedAt }?.let(::add)
        precipitationObservations.maxOfOrNull { it.interval.end }?.let(::add)
    }.maxOrNull()
