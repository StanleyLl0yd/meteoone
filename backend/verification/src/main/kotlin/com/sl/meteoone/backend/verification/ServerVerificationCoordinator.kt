package com.sl.meteoone.backend.verification

import com.sl.meteoone.backend.provider.ProviderGateway
import com.sl.meteoone.backend.provider.ProviderGatewayRequest
import com.sl.meteoone.backend.provider.ProviderGatewayResult
import com.sl.meteoone.backend.provider.ProviderResponseValidator
import com.sl.meteoone.backend.provider.ServerProviderGateway
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ForecastTarget
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.forecast.data.openmeteo.OPEN_METEO_SINGLE_RUN_MINIMUM_REQUEST_SPACING
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoModel
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoSingleRunMapper
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoSingleRunRequestPlanner
import com.sl.meteoone.verification.data.ghcnh.GHCNH_SOURCE_ID
import com.sl.meteoone.verification.data.ghcnh.GhcnhObservationSource
import com.sl.meteoone.verification.data.ghcnh.GhcnhObservationsResult
import com.sl.meteoone.verification.data.ghcnh.GhcnhStationCandidate
import com.sl.meteoone.verification.data.ghcnh.GhcnhStationCandidateRanker
import com.sl.meteoone.verification.data.ghcnh.GhcnhStationCandidateSource
import com.sl.meteoone.verification.data.ghcnh.GhcnhStationCandidatesResult
import com.sl.meteoone.verification.domain.VerificationSample
import com.sl.meteoone.verification.domain.VerificationSampleMatcher
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TARGET_COMPLETE_RUNS = 14
private const val MAX_RUNS_PER_REFRESH = 2
private const val RUN_LOOKBACK_DAYS = 30L
private val SAMPLE_WINDOW: Duration = Duration.ofDays(RUN_LOOKBACK_DAYS)
private val OBSERVATION_REFRESH_AGE: Duration = Duration.ofDays(7)
private val PUBLICATION_GUARD: Duration = Duration.ofHours(7)
private val MODEL_FAMILIES = setOf(
    ModelFamily.ECMWF_IFS,
    ModelFamily.DWD_ICON,
    ModelFamily.NOAA_GFS,
)
private val EXACT_RUN_MODELS = listOf(
    OpenMeteoModel.ECMWF_IFS,
    OpenMeteoModel.DWD_ICON_GLOBAL,
    OpenMeteoModel.NOAA_GFS_GLOBAL,
)

fun interface ServerVerificationSampleProvider {
    suspend fun prepareSamples(
        target: ForecastTarget,
        evaluatedAt: Instant,
    ): List<VerificationSample>

    companion object {
        val NONE: ServerVerificationSampleProvider =
            ServerVerificationSampleProvider { _, _ -> emptyList() }
    }
}

internal fun interface ServerExactRunSource {
    suspend fun acquire(
        target: ForecastTarget,
        modelRuns: List<Instant>,
        capturedAt: Instant,
    ): List<SourceForecast>
}

internal fun interface ServerStationCandidateSource {
    suspend fun candidates(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
    ): GhcnhStationCandidatesResult
}

internal fun interface ServerObservationSource {
    suspend fun observations(
        candidates: List<GhcnhStationCandidate>,
        startInclusive: Instant,
        endExclusive: Instant,
    ): GhcnhObservationsResult
}

internal class GatewayServerExactRunSource(
    private val gateway: ProviderGateway,
    private val mapper: OpenMeteoSingleRunMapper = OpenMeteoSingleRunMapper(),
) : ServerExactRunSource {
    override suspend fun acquire(
        target: ForecastTarget,
        modelRuns: List<Instant>,
        capturedAt: Instant,
    ): List<SourceForecast> {
        require(modelRuns.size <= MAX_RUNS_PER_REFRESH) {
            "Server exact-run refresh exceeds the bounded run budget"
        }
        require(modelRuns == modelRuns.distinct().sorted()) {
            "Server exact-run acquisition runs must be unique and chronological"
        }

        val location = ForecastLocation(
            latitude = target.coordinate.latitude,
            longitude = target.coordinate.longitude,
            elevationMeters = target.elevationMeters,
            timeZoneId = target.timeZoneId,
        )
        val forecasts = mutableListOf<SourceForecast>()
        modelRuns.forEach { run ->
            EXACT_RUN_MODELS.forEach { model ->
                val plan = OpenMeteoSingleRunRequestPlanner.plan(
                    model = model,
                    coordinate = target.coordinate,
                    modelRun = run,
                )
                var mapped: SourceForecast? = null
                val result = gateway.execute(
                    request = ProviderGatewayRequest(
                        provider = plan.provider,
                        modelFamily = plan.modelFamily,
                        uri = plan.uri,
                        maxResponseBytes = plan.maxResponseBytes,
                        expectedStatusCodes = setOf(200),
                        minimumRequestSpacing =
                            OPEN_METEO_SINGLE_RUN_MINIMUM_REQUEST_SPACING,
                    ),
                    responseValidator = ProviderResponseValidator { response ->
                        try {
                            mapped = mapper.map(
                                request = plan,
                                capturedAt = capturedAt,
                                location = location,
                                payload = response.body.toString(Charsets.UTF_8),
                            )
                            true
                        } catch (_: Exception) {
                            false
                        } catch (_: LinkageError) {
                            false
                        }
                    },
                )
                when (result) {
                    is ProviderGatewayResult.Success -> {
                        mapped?.let(forecasts::add)
                    }

                    is ProviderGatewayResult.Failure -> Unit
                }
            }
        }
        return forecasts
    }
}

class ServerVerificationCoordinator internal constructor(
    private val store: ServerVerificationEvidenceStore,
    private val exactRunSource: ServerExactRunSource,
    private val stationCandidateSource: ServerStationCandidateSource,
    private val observationSource: ServerObservationSource,
    private val sampleMatcher: VerificationSampleMatcher = VerificationSampleMatcher(),
    private val storedStationRanker: GhcnhStationCandidateRanker = GhcnhStationCandidateRanker(),
) : ServerVerificationSampleProvider {
    override suspend fun prepareSamples(
        target: ForecastTarget,
        evaluatedAt: Instant,
    ): List<VerificationSample> {
        val coordinate = target.coordinate
        val fromInclusive = evaluatedAt.minus(SAMPLE_WINDOW)
        var history = store.forecastsSince(
            coordinate = coordinate,
            modelRunFromInclusive = fromInclusive,
        )
        val runsToAcquire = planRunsToAcquire(history, evaluatedAt)
        val attemptedRunAcquisition = runsToAcquire.isNotEmpty()

        if (attemptedRunAcquisition) {
            val acquired = exactRunSource.acquire(
                target = target,
                modelRuns = runsToAcquire,
                capturedAt = evaluatedAt,
            )
            store.archiveForecasts(
                coordinate = coordinate,
                forecasts = acquired,
            )
            history = store.forecastsSince(
                coordinate = coordinate,
                modelRunFromInclusive = fromInclusive,
            )
        }

        var observations = readBestStoredSeries(
            coordinate = coordinate,
            elevationMeters = target.elevationMeters,
            fromInclusive = fromInclusive,
            endInclusive = evaluatedAt,
        )
        val shouldRefreshObservations =
            observations == null ||
                (
                    attemptedRunAcquisition &&
                        observations.latestEvidenceAt()
                            ?.isBefore(evaluatedAt.minus(OBSERVATION_REFRESH_AGE)) != false
                )

        if (shouldRefreshObservations) {
            observations = refreshObservations(
                coordinate = coordinate,
                elevationMeters = target.elevationMeters,
                startInclusive = fromInclusive,
                endExclusive = evaluatedAt,
            ) ?: observations
        }

        val selected = observations ?: return emptyList()
        return history.flatMap { run ->
            sampleMatcher.match(
                forecast = run.evidence,
                station = selected.station,
                surfaceObservations = selected.surfaceObservations,
                precipitationObservations = selected.precipitationObservations,
            )
        }
    }

    private suspend fun readBestStoredSeries(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        fromInclusive: Instant,
        endInclusive: Instant,
    ): ServerStoredObservationSeries? {
        val ranked = storedStationRanker.rankStoredStations(
            target = coordinate,
            targetElevationMeters = elevationMeters,
            stations = store.stations(GHCNH_SOURCE_ID)
                .filter { station -> station.sourceId == GHCNH_SOURCE_ID },
        )
        ranked.forEach { station ->
            val series = store.observationsSince(
                sourceId = station.sourceId,
                stationId = station.stationId,
                fromInclusive = fromInclusive,
            )?.boundedAt(endInclusive)
            if (series != null && series.hasUsableEvidence()) {
                return series
            }
        }
        return null
    }

    private suspend fun refreshObservations(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        startInclusive: Instant,
        endExclusive: Instant,
    ): ServerStoredObservationSeries? {
        if (!startInclusive.isBefore(endExclusive)) return null
        val candidates = when (
            val result = stationCandidateSource.candidates(
                coordinate = coordinate,
                elevationMeters = elevationMeters,
            )
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

        store.archiveObservations(
            station = series.station,
            surfaceObservations = series.surfaceObservations,
            precipitationObservations = series.precipitationObservations,
        )
        return ServerStoredObservationSeries(
            station = series.station,
            surfaceObservations = series.surfaceObservations,
            precipitationObservations = series.precipitationObservations,
        )
    }
}

fun productionServerVerificationSampleProvider(
    store: ServerVerificationEvidenceStore =
        InMemoryServerVerificationEvidenceStore(),
): ServerVerificationSampleProvider {
    val gateway = ServerProviderGateway.production()
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    val stationCandidates = GhcnhStationCandidateSource.default()
    val observations = GhcnhObservationSource.default()
    return ServerVerificationCoordinator(
        store = store,
        exactRunSource = GatewayServerExactRunSource(gateway),
        stationCandidateSource = ServerStationCandidateSource { coordinate, elevation ->
            withContext(ioDispatcher) {
                stationCandidates.candidates(
                    target = coordinate,
                    targetElevationMeters = elevation,
                )
            }
        },
        observationSource = ServerObservationSource { candidates, start, end ->
            withContext(ioDispatcher) {
                observations.observations(
                    candidates = candidates,
                    startInclusive = start,
                    endExclusive = end,
                )
            }
        },
    )
}

private fun planRunsToAcquire(
    history: List<ServerStoredForecastRun>,
    evaluatedAt: Instant,
): List<Instant> {
    val completeRuns = history
        .filter { run -> run.evidence.provider == ForecastProvider.OPEN_METEO }
        .groupBy { run -> run.evidence.modelRun }
        .filterValues { runs ->
            runs.map { run -> run.evidence.modelFamily }.toSet()
                .containsAll(MODEL_FAMILIES)
        }
        .keys

    val latestEligibleDate = evaluatedAt
        .minus(PUBLICATION_GUARD)
        .atOffset(ZoneOffset.UTC)
        .toLocalDate()
    val recentRuns = (0L until RUN_LOOKBACK_DAYS).map { dayOffset ->
        latestEligibleDate
            .minusDays(dayOffset)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
    }

    val selected = linkedSetOf<Instant>()
    val latest = recentRuns.first()
    if (latest !in completeRuns) selected += latest

    if (completeRuns.size < TARGET_COMPLETE_RUNS) {
        recentRuns.forEach { run ->
            if (selected.size >= MAX_RUNS_PER_REFRESH) return@forEach
            if (run !in completeRuns) selected += run
        }
    }
    return selected
        .take(MAX_RUNS_PER_REFRESH)
        .sorted()
}

private fun ServerStoredObservationSeries.boundedAt(
    endInclusive: Instant,
): ServerStoredObservationSeries =
    copy(
        surfaceObservations = surfaceObservations.filter { observation ->
            !observation.observedAt.isAfter(endInclusive)
        },
        precipitationObservations = precipitationObservations.filter { observation ->
            !observation.interval.end.isAfter(endInclusive)
        },
    )

private fun ServerStoredObservationSeries.hasUsableEvidence(): Boolean =
    surfaceObservations.isNotEmpty() || precipitationObservations.isNotEmpty()

private fun ServerStoredObservationSeries.latestEvidenceAt(): Instant? =
    buildList {
        surfaceObservations.maxOfOrNull { observation -> observation.observedAt }?.let(::add)
        precipitationObservations.maxOfOrNull { observation -> observation.interval.end }?.let(::add)
    }.maxOrNull()
