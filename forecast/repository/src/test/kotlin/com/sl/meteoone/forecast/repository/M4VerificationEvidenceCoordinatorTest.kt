package com.sl.meteoone.forecast.repository

import com.sl.meteoone.core.database.ForecastVerificationArchiveResult
import com.sl.meteoone.core.database.ForecastVerificationHistoryStore
import com.sl.meteoone.core.database.StoredVerificationForecastPoint
import com.sl.meteoone.core.database.StoredVerificationForecastRun
import com.sl.meteoone.core.database.StoredVerificationObservationSeries
import com.sl.meteoone.core.database.VerificationObservationArchiveResult
import com.sl.meteoone.core.database.VerificationObservationStore
import com.sl.meteoone.core.database.VerificationSampleProducer
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.forecast.data.execution.ExactRunAcquisitionResult
import com.sl.meteoone.verification.data.ghcnh.GHCNH_SOURCE_ID
import com.sl.meteoone.verification.data.ghcnh.GhcnhObservationSeries
import com.sl.meteoone.verification.data.ghcnh.GhcnhObservationsResult
import com.sl.meteoone.verification.data.ghcnh.GhcnhStationCandidate
import com.sl.meteoone.verification.data.ghcnh.GhcnhStationCandidatesResult
import com.sl.meteoone.verification.data.ghcnh.GhcnhStationMetadata
import com.sl.meteoone.verification.domain.LeadTimeBucket
import com.sl.meteoone.verification.domain.MeteorologicalSeason
import com.sl.meteoone.verification.domain.ObservationStation
import com.sl.meteoone.verification.domain.ScalarVerificationSample
import com.sl.meteoone.verification.domain.SurfaceObservation
import com.sl.meteoone.verification.domain.VerificationContext
import com.sl.meteoone.verification.domain.VerificationParameter
import com.sl.meteoone.verification.domain.VerificationSample
import com.sl.meteoone.verification.domain.VerificationWeightRequest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class M4VerificationEvidenceCoordinatorTest {
    private val coordinate = ForecastCoordinate(59.9, 30.3)
    private val evaluatedAt = Instant.parse("2026-09-21T12:00:00Z")
    private val station = ObservationStation(
        sourceId = GHCNH_SOURCE_ID,
        stationId = "RSM00026063",
        latitude = 59.9667,
        longitude = 30.3,
        elevationMeters = 4.0,
    )

    @Test
    fun bootstrapAcquiresAtMostTwoMissingRunsAndReusesStoredStation() = runBlocking {
        val history = FakeHistoryStore()
        val observations = FakeObservationStore(
            initial = listOf(
                StoredVerificationObservationSeries(
                    station = station,
                    surfaceObservations = listOf(
                        surface(station, evaluatedAt.minus(Duration.ofHours(1))),
                    ),
                    precipitationObservations = emptyList(),
                ),
            ),
        )
        val producer = RecordingSampleProducer()
        var requestedRuns = emptyList<Instant>()
        val coordinator = M4VerificationEvidenceCoordinator(
            historyStore = history,
            observationStore = observations,
            exactRunSource = M4ExactRunSource { _, _, _, runs, capturedAt ->
                requestedRuns = runs
                ExactRunAcquisitionResult(
                    forecasts = runs.flatMap { run ->
                        M4_MODEL_TEST_FAMILIES.map { family ->
                            sourceForecast(family, run, capturedAt)
                        }
                    },
                    failedSources = emptyList(),
                )
            },
            stationCandidateSource = M4StationCandidateSource { _, _ ->
                error("stored usable station must avoid station-catalog network work")
            },
            observationSource = M4ObservationSource { _, _, _ ->
                error("stored fresh station must avoid observation network work")
            },
            sampleProducer = producer,
        )

        coordinator.prepareSamples(
            coordinate = coordinate,
            elevationMeters = 12,
            timeZoneId = "Europe/Moscow",
            evaluatedAt = evaluatedAt,
        )

        assertEquals(
            listOf(
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"),
            ),
            requestedRuns,
        )
        assertEquals(1, history.archiveCalls)
        assertEquals(6, producer.producedForecasts.size)
        assertEquals(0, observations.archiveCalls)
    }

    @Test
    fun missingStoredObservationBootstrapsGhcnhAndArchivesSelectedSeries() = runBlocking {
        val completeHistory = (0L until 14L).flatMap { offset ->
            val run = Instant.parse("2026-09-21T00:00:00Z").minus(Duration.ofDays(offset))
            M4_MODEL_TEST_FAMILIES.map { family -> storedRun(family, run) }
        }
        val history = FakeHistoryStore(completeHistory)
        val observations = FakeObservationStore()
        val producer = RecordingSampleProducer()
        val metadata = GhcnhStationMetadata(
            stationId = station.stationId,
            latitude = station.latitude,
            longitude = station.longitude,
            elevationMeters = station.elevationMeters,
            state = null,
            name = station.stationId,
            gsn = false,
            hcnCrn = null,
            wmoId = null,
            icao = null,
        )
        val candidate = GhcnhStationCandidate(
            station = metadata,
            distanceKm = 7.0,
            elevationDeltaMeters = 8.0,
        )
        var candidateCalls = 0
        var observationCalls = 0
        val coordinator = M4VerificationEvidenceCoordinator(
            historyStore = history,
            observationStore = observations,
            exactRunSource = M4ExactRunSource { _, _, _, _, _ ->
                error("complete recent history must not reacquire exact runs")
            },
            stationCandidateSource = M4StationCandidateSource { _, _ ->
                candidateCalls += 1
                GhcnhStationCandidatesResult.Available(listOf(candidate))
            },
            observationSource = M4ObservationSource { candidates, _, _ ->
                observationCalls += 1
                assertEquals(listOf(candidate), candidates)
                GhcnhObservationsResult.Available(
                    GhcnhObservationSeries(
                        stationMetadata = metadata,
                        station = station,
                        surfaceObservations = listOf(
                            surface(station, evaluatedAt.minus(Duration.ofHours(2))),
                        ),
                        precipitationObservations = emptyList(),
                        evidence = emptyList(),
                    ),
                )
            },
            sampleProducer = producer,
        )

        coordinator.prepareSamples(
            coordinate = coordinate,
            elevationMeters = 12,
            timeZoneId = "Europe/Moscow",
            evaluatedAt = evaluatedAt,
        )

        assertEquals(1, candidateCalls)
        assertEquals(1, observationCalls)
        assertEquals(1, observations.archiveCalls)
        assertEquals(42, producer.producedForecasts.size)
        assertEquals(listOf(station), observations.readStations(GHCNH_SOURCE_ID))
    }

    @Test
    fun refreshScopedSamplesAreVisibleOnlyDuringDelegateForecast() = runBlocking {
        val sample = sample()
        val sampleSource = RefreshScopedVerificationWeightSampleSource()
        val request = weightRequest()
        var visibleDuringDelegate = emptyList<VerificationSample>()
        val source = M4ForecastRefreshSource(
            coordinator = M4VerificationEvidenceSource { _, _, _, _ -> listOf(sample) },
            sampleSource = sampleSource,
            delegate = ForecastRefreshSource { _, _, _, _ ->
                visibleDuringDelegate = sampleSource.samples(request).toList()
                ForecastRefreshSourceResult.Unavailable
            },
        )

        assertEquals(
            ForecastRefreshSourceResult.Unavailable,
            source.forecast(coordinate, null, "UTC", evaluatedAt),
        )
        assertEquals(listOf(sample), visibleDuringDelegate)
        assertEquals(emptyList(), sampleSource.samples(request).toList())
    }

    @Test
    fun verificationFailureFallsBackToEmptyEvidenceButCancellationPropagates() = runBlocking {
        val sampleSource = RefreshScopedVerificationWeightSampleSource()
        sampleSource.replace(listOf(sample()))
        val request = weightRequest()
        var delegateEvidence = listOf<VerificationSample>(sample())
        val source = M4ForecastRefreshSource(
            coordinator = M4VerificationEvidenceSource { _, _, _, _ ->
                error("planned verification preparation failure")
            },
            sampleSource = sampleSource,
            delegate = ForecastRefreshSource { _, _, _, _ ->
                delegateEvidence = sampleSource.samples(request).toList()
                ForecastRefreshSourceResult.Unavailable
            },
        )

        source.forecast(coordinate, null, "UTC", evaluatedAt)
        assertEquals(emptyList(), delegateEvidence)
        assertEquals(emptyList(), sampleSource.samples(request).toList())

        val cancelled = M4ForecastRefreshSource(
            coordinator = M4VerificationEvidenceSource { _, _, _, _ ->
                throw CancellationException("planned")
            },
            sampleSource = sampleSource,
            delegate = ForecastRefreshSource { _, _, _, _ ->
                error("delegate must not run after cancellation")
            },
        )
        var cancellationObserved = false
        try {
            cancelled.forecast(coordinate, null, "UTC", evaluatedAt)
        } catch (_: CancellationException) {
            cancellationObserved = true
        }
        assertTrue(cancellationObserved)
    }

    private fun sourceForecast(
        family: ModelFamily,
        run: Instant,
        capturedAt: Instant,
    ): SourceForecast = SourceForecast(
        origin = ForecastOrigin(
            provider = ForecastProvider.OPEN_METEO,
            modelFamily = family,
            modelRun = run,
            generatedAt = capturedAt,
        ),
        location = ForecastLocation(
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            elevationMeters = 12,
            timeZoneId = "Europe/Moscow",
        ),
        hourly = listOf(
            HourlyWeatherPoint(
                time = run.plus(Duration.ofHours(6)),
                temperatureC = 10.0,
                feelsLikeC = null,
                dewPointC = null,
                humidityPercent = null,
                pressureSeaLevelHpa = null,
                windSpeedMps = null,
                windGustMps = null,
                windDirectionDegrees = null,
                precipitationMm = null,
                precipitationProbabilityPercent = null,
                cloudCoverPercent = null,
                visibilityMeters = null,
            ),
        ),
    )

    private fun storedRun(
        family: ModelFamily,
        run: Instant,
    ): StoredVerificationForecastRun = StoredVerificationForecastRun(
        coordinate = coordinate,
        provider = ForecastProvider.OPEN_METEO,
        modelFamily = family,
        modelRun = run,
        firstCapturedAt = run.plus(Duration.ofHours(8)),
        elevationMeters = 12,
        timeZoneId = "Europe/Moscow",
        hourly = listOf(
            StoredVerificationForecastPoint(
                validTime = run.plus(Duration.ofHours(6)),
                leadTime = Duration.ofHours(6),
                temperatureC = 10.0,
                pressureSeaLevelHpa = null,
                windSpeedMps = null,
                windDirectionDegrees = null,
                precipitationMm = null,
                precipitationInterval = null,
            ),
        ),
    )

    private fun surface(
        station: ObservationStation,
        observedAt: Instant,
    ): SurfaceObservation = SurfaceObservation(
        station = station,
        observedAt = observedAt,
        temperatureC = 9.0,
        pressureSeaLevelHpa = null,
        windSpeedMps = null,
        windDirectionDegrees = null,
    )

    private fun sample(): VerificationSample {
        val run = evaluatedAt.minus(Duration.ofDays(1))
        val validTime = run.plus(Duration.ofHours(12))
        return ScalarVerificationSample(
            context = VerificationContext(
                coordinate = coordinate,
                provider = ForecastProvider.OPEN_METEO,
                modelFamily = ModelFamily.NOAA_GFS,
                modelRun = run,
                validTime = validTime,
                timeZoneId = "UTC",
            ),
            station = station,
            parameter = VerificationParameter.TEMPERATURE,
            observedAt = validTime,
            predicted = 10.0,
            observed = 9.0,
        )
    }

    private fun weightRequest() = VerificationWeightRequest(
        coordinate = coordinate,
        season = MeteorologicalSeason.AUTUMN,
        parameter = VerificationParameter.TEMPERATURE,
        leadBucket = LeadTimeBucket.H6_24,
        modelFamilies = setOf(ModelFamily.NOAA_GFS, ModelFamily.DWD_ICON),
        evaluatedAt = evaluatedAt,
    )
}

private class FakeHistoryStore(
    initial: List<StoredVerificationForecastRun> = emptyList(),
) : ForecastVerificationHistoryStore {
    private val runs = initial.toMutableList()
    var archiveCalls: Int = 0
        private set

    override suspend fun archive(
        coordinate: ForecastCoordinate,
        forecasts: List<SourceForecast>,
    ): ForecastVerificationArchiveResult {
        archiveCalls += 1
        forecasts.forEach { forecast ->
            val run = requireNotNull(forecast.origin.modelRun)
            val stored = StoredVerificationForecastRun(
                coordinate = coordinate,
                provider = forecast.origin.provider,
                modelFamily = forecast.origin.modelFamily,
                modelRun = run,
                firstCapturedAt = forecast.origin.generatedAt,
                elevationMeters = forecast.location.elevationMeters,
                timeZoneId = forecast.location.timeZoneId,
                hourly = forecast.hourly.map { point ->
                    StoredVerificationForecastPoint(
                        validTime = point.time,
                        leadTime = Duration.between(run, point.time),
                        temperatureC = point.temperatureC,
                        pressureSeaLevelHpa = point.pressureSeaLevelHpa,
                        windSpeedMps = point.windSpeedMps,
                        windDirectionDegrees = point.windDirectionDegrees,
                        precipitationMm = point.precipitationMm,
                        precipitationInterval = point.precipitationInterval,
                    )
                },
            )
            runs.removeAll {
                it.provider == stored.provider &&
                    it.modelFamily == stored.modelFamily &&
                    it.modelRun == stored.modelRun
            }
            runs += stored
        }
        return ForecastVerificationArchiveResult(
            insertedRuns = forecasts.size,
            insertedPoints = forecasts.sumOf { it.hourly.size },
            existingPoints = 0,
            skippedWithoutModelRun = 0,
            skippedExpiredRuns = 0,
            prunedRuns = 0,
        )
    }

    override suspend fun readSince(
        coordinate: ForecastCoordinate,
        modelRunFromInclusive: Instant,
    ): List<StoredVerificationForecastRun> =
        runs.filter {
            it.coordinate == coordinate && !it.modelRun.isBefore(modelRunFromInclusive)
        }
}

private class FakeObservationStore(
    initial: List<StoredVerificationObservationSeries> = emptyList(),
) : VerificationObservationStore {
    private val series = initial.associateBy { it.station.sourceId to it.station.stationId }
        .toMutableMap()
    var archiveCalls: Int = 0
        private set

    override suspend fun archive(
        station: ObservationStation,
        surfaceObservations: List<SurfaceObservation>,
        precipitationObservations: List<com.sl.meteoone.verification.domain.PrecipitationObservation>,
    ): VerificationObservationArchiveResult {
        archiveCalls += 1
        series[station.sourceId to station.stationId] = StoredVerificationObservationSeries(
            station = station,
            surfaceObservations = surfaceObservations,
            precipitationObservations = precipitationObservations,
        )
        return VerificationObservationArchiveResult(
            stationInserted = true,
            insertedSurface = surfaceObservations.size,
            enrichedSurface = 0,
            existingSurface = 0,
            skippedExpiredSurface = 0,
            insertedPrecipitation = precipitationObservations.size,
            existingPrecipitation = 0,
            skippedExpiredPrecipitation = 0,
            prunedSurface = 0,
            prunedPrecipitation = 0,
            prunedStations = 0,
        )
    }

    override suspend fun readStations(sourceId: String): List<ObservationStation> =
        series.values
            .map(StoredVerificationObservationSeries::station)
            .filter { it.sourceId == sourceId }
            .sortedBy(ObservationStation::stationId)

    override suspend fun readSince(
        sourceId: String,
        stationId: String,
        fromInclusive: Instant,
    ): StoredVerificationObservationSeries? =
        series[sourceId to stationId]?.let { stored ->
            StoredVerificationObservationSeries(
                station = stored.station,
                surfaceObservations = stored.surfaceObservations.filter {
                    !it.observedAt.isBefore(fromInclusive)
                },
                precipitationObservations = stored.precipitationObservations.filter {
                    !it.interval.end.isBefore(fromInclusive)
                },
            )
        }
}

private class RecordingSampleProducer : VerificationSampleProducer {
    val producedForecasts = mutableListOf<StoredVerificationForecastRun>()

    override fun produce(
        forecast: StoredVerificationForecastRun,
        observations: StoredVerificationObservationSeries,
    ): List<VerificationSample> {
        producedForecasts += forecast
        return emptyList()
    }
}

private val M4_MODEL_TEST_FAMILIES = listOf(
    ModelFamily.ECMWF_IFS,
    ModelFamily.DWD_ICON,
    ModelFamily.NOAA_GFS,
)
