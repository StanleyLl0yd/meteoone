package com.sl.meteoone.backend.verification

import com.sl.meteoone.backend.provider.ProviderGateway
import com.sl.meteoone.backend.provider.ProviderGatewayFailureReason
import com.sl.meteoone.backend.provider.ProviderGatewayRequest
import com.sl.meteoone.backend.provider.ProviderGatewayResponse
import com.sl.meteoone.backend.provider.ProviderGatewayResult
import com.sl.meteoone.backend.provider.ProviderResponseValidator
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ForecastTarget
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.verification.data.ghcnh.GhcnhObservationsResult
import com.sl.meteoone.verification.data.ghcnh.GhcnhStationCandidatesResult
import com.sl.meteoone.verification.domain.ObservationStation
import com.sl.meteoone.verification.domain.SurfaceObservation
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerVerificationCoordinatorTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val coordinate = ForecastCoordinate(59.9, 30.3)
    private val target = ForecastTarget(
        coordinate = coordinate,
        elevationMeters = 12,
        timeZoneId = "Europe/Moscow",
    )
    private val location = ForecastLocation(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        elevationMeters = 12,
        timeZoneId = "Europe/Moscow",
    )
    private val station = ObservationStation(
        sourceId = "noaa-ncei-ghcnh",
        stationId = "RSM00026063",
        latitude = 59.9667,
        longitude = 30.3,
        elevationMeters = 4.0,
    )

    @Test
    fun refreshAcquiresAtMostTwoMissingRunsAndProducesSamplesFromStoredTruth() = runBlocking {
        val store = store()
        val expectedRuns = listOf(
            Instant.parse("2026-09-30T00:00:00Z"),
            Instant.parse("2026-10-01T00:00:00Z"),
        )
        archiveObservations(store, expectedRuns)

        val requested = mutableListOf<List<Instant>>()
        val coordinator = coordinator(
            store = store,
            exactRunSource = ServerExactRunSource { _, runs, capturedAt ->
                requested += runs
                runs.flatMap { run ->
                    FAMILIES.map { family ->
                        forecast(
                            family = family,
                            run = run,
                            generatedAt = capturedAt,
                        )
                    }
                }
            },
        )

        val samples = coordinator.prepareSamples(target, now)

        assertEquals(listOf(expectedRuns), requested)
        assertEquals(6, samples.size)
        assertEquals(
            FAMILIES,
            samples.map { sample -> sample.context.modelFamily }.toSet(),
        )
        assertEquals(
            expectedRuns.toSet(),
            samples.map { sample -> sample.context.modelRun }.toSet(),
        )
    }

    @Test
    fun fourteenCompleteRunsDoNotTriggerMoreExactRunAcquisition() = runBlocking {
        val store = store()
        val runs = (0L until 14L).map { dayOffset ->
            Instant.parse("2026-10-01T00:00:00Z").minus(Duration.ofDays(dayOffset))
        }.sorted()
        runs.forEach { run ->
            store.archiveForecasts(
                coordinate = coordinate,
                forecasts = FAMILIES.map { family ->
                    forecast(
                        family = family,
                        run = run,
                        generatedAt = run.plus(Duration.ofHours(8)),
                    )
                },
            )
        }
        archiveObservations(store, runs)

        var acquisitionCalls = 0
        val coordinator = coordinator(
            store = store,
            exactRunSource = ServerExactRunSource { _, _, _ ->
                acquisitionCalls += 1
                emptyList()
            },
        )

        val samples = coordinator.prepareSamples(target, now)

        assertEquals(0, acquisitionCalls)
        assertEquals(42, samples.size)
    }

    @Test
    fun exactRunSemanticFailureIsRejectedInsideGatewayValidation() = runBlocking {
        val gateway = ValidatingFakeGateway()
        val source = GatewayServerExactRunSource(gateway)

        val forecasts = source.acquire(
            target = target,
            modelRuns = listOf(Instant.parse("2026-09-30T00:00:00Z")),
            capturedAt = now,
        )

        assertTrue(forecasts.isEmpty())
        assertEquals(3, gateway.requests.size)
        assertTrue(gateway.requests.all { request ->
            request.provider == ForecastProvider.OPEN_METEO &&
                request.expectedStatusCodes == setOf(200) &&
                request.minimumRequestSpacing == Duration.ofSeconds(1)
        })
        assertEquals(3, gateway.semanticRejections)
    }

    @Test
    fun cancellationFromEvidenceAcquisitionPropagates() = runBlocking {
        val coordinator = coordinator(
            store = store(),
            exactRunSource = ServerExactRunSource { _, _, _ ->
                throw CancellationException("cancel")
            },
        )

        assertFailsWith<CancellationException> {
            coordinator.prepareSamples(target, now)
        }
        Unit
    }

    private fun coordinator(
        store: ServerVerificationEvidenceStore,
        exactRunSource: ServerExactRunSource,
    ): ServerVerificationCoordinator =
        ServerVerificationCoordinator(
            store = store,
            exactRunSource = exactRunSource,
            stationCandidateSource = ServerStationCandidateSource { _, _ ->
                GhcnhStationCandidatesResult.Unavailable
            },
            observationSource = ServerObservationSource { _, _, _ ->
                GhcnhObservationsResult.Unavailable
            },
        )

    private fun store(): InMemoryServerVerificationEvidenceStore =
        InMemoryServerVerificationEvidenceStore(
            clock = Clock.fixed(now, ZoneOffset.UTC),
            retention = Duration.ofDays(180),
            maxCoordinates = 8,
            maxRunsPerCoordinate = 128,
            maxStations = 8,
            maxSurfacePerStation = 2048,
            maxPrecipitationPerStation = 2048,
        )

    private suspend fun archiveObservations(
        store: ServerVerificationEvidenceStore,
        runs: List<Instant>,
    ) {
        store.archiveObservations(
            station = station,
            surfaceObservations = runs.map { run ->
                SurfaceObservation(
                    station = station,
                    observedAt = run.plus(Duration.ofHours(12)),
                    temperatureC = 0.0,
                    pressureSeaLevelHpa = null,
                    windSpeedMps = null,
                    windDirectionDegrees = null,
                )
            },
            precipitationObservations = emptyList(),
        )
    }

    private fun forecast(
        family: ModelFamily,
        run: Instant,
        generatedAt: Instant,
    ): SourceForecast =
        SourceForecast(
            origin = ForecastOrigin(
                provider = ForecastProvider.OPEN_METEO,
                modelFamily = family,
                modelRun = run,
                generatedAt = generatedAt,
            ),
            location = location,
            hourly = listOf(
                HourlyWeatherPoint(
                    time = run.plus(Duration.ofHours(12)),
                    temperatureC = when (family) {
                        ModelFamily.ECMWF_IFS -> 1.0
                        ModelFamily.DWD_ICON -> 2.0
                        ModelFamily.NOAA_GFS -> 3.0
                        ModelFamily.UNKNOWN -> error("Unknown family")
                    },
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

    private class ValidatingFakeGateway : ProviderGateway {
        val requests = mutableListOf<ProviderGatewayRequest>()
        var semanticRejections = 0
            private set

        override suspend fun execute(
            request: ProviderGatewayRequest,
            responseValidator: ProviderResponseValidator,
        ): ProviderGatewayResult {
            requests += request
            val response = ProviderGatewayResponse(
                provider = request.provider,
                modelFamily = request.modelFamily,
                statusCode = 200,
                headers = mapOf("Content-Type" to listOf("application/json")),
                body = "{}".encodeToByteArray(),
            )
            return if (responseValidator.isValid(response)) {
                ProviderGatewayResult.Success(response)
            } else {
                semanticRejections += 1
                ProviderGatewayResult.Failure(
                    provider = request.provider,
                    modelFamily = request.modelFamily,
                    reason = ProviderGatewayFailureReason.INVALID_RESPONSE,
                )
            }
        }
    }

    private companion object {
        val FAMILIES = setOf(
            ModelFamily.ECMWF_IFS,
            ModelFamily.DWD_ICON,
            ModelFamily.NOAA_GFS,
        )
    }
}
