package com.sl.meteoone.backend.orchestration

import com.sl.meteoone.backend.gateway.SingleFlightForecastGatewayCache
import com.sl.meteoone.backend.provideradapter.ServerForecastAdapterFailureReason
import com.sl.meteoone.backend.provideradapter.ServerForecastAdapterResult
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ForecastTarget
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.forecast.domain.ForecastSourceIdentity
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackendForecastOrchestratorTest {
    private val requestedAt = Instant.parse("2026-09-25T12:15:00Z")
    private val horizonStart = Instant.parse("2026-09-25T13:00:00Z")
    private val target = ForecastTarget(
        coordinate = ForecastCoordinate(59.9, 30.3),
        elevationMeters = 10,
        timeZoneId = "Europe/Moscow",
    )

    @Test
    fun centralizesEstablishedSourceOrderRunPolicyAndModelFamilyDeduplication() = runBlocking {
        val sources = FakeSources(
            outcomes = successfulOutcomes(
                noaaDirectTemperature = 50.0,
                ecmwfDirectTemperature = 60.0,
                dwdDirectTemperature = 70.0,
            ),
        )
        val result = orchestrator(sources).forecast(target, requestedAt)

        val available = assertIs<BackendForecastResult.Available>(result)
        assertEquals(72, available.forecast.hourly.size)
        assertEquals(6, available.sourceForecasts.size)
        assertEquals(emptyList(), available.failedSources)
        assertEquals(
            listOf(
                identity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS),
                identity(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS),
                identity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS),
                identity(ForecastProvider.OPEN_METEO, ModelFamily.ECMWF_IFS),
                identity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON),
                identity(ForecastProvider.OPEN_METEO, ModelFamily.DWD_ICON),
            ),
            sources.attempts,
        )

        val expectedRun = Instant.parse("2026-09-25T00:00:00Z")
        assertEquals(expectedRun, sources.noaaRun)
        assertEquals(13, sources.noaaHour)
        assertEquals(expectedRun, sources.ecmwfRun)
        assertEquals(15, sources.ecmwfHour)
        assertEquals(expectedRun, sources.dwdRun)
        assertEquals(13, sources.dwdHour)

        val hour13 = available.forecast.hourly.first { it.weather.time == horizonStart }
        assertEquals(3, hour13.independentEvidenceCount)
        assertEquals(3, hour13.providerCount)
        assertEquals(100.0 / 3.0, hour13.weather.temperatureC, absoluteTolerance = 1e-9)
    }

    @Test
    fun usesNextValidOpenMeteoBaselineAndPreservesTypedPartialFailures() = runBlocking {
        val outcomes = successfulOutcomes().toMutableMap()
        outcomes[identity(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS)] =
            failure(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS, ServerForecastAdapterFailureReason.IO)
        outcomes[identity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS)] =
            failure(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS, ServerForecastAdapterFailureReason.CIRCUIT_OPEN)

        val result = orchestrator(FakeSources(outcomes)).forecast(target, requestedAt)

        val available = assertIs<BackendForecastResult.Available>(result)
        assertEquals(72, available.forecast.hourly.size)
        assertTrue(
            available.failedSources.contains(
                BackendFailedSource(
                    identity(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS),
                    BackendForecastFailureReason.IO,
                ),
            ),
        )
        assertTrue(
            available.failedSources.contains(
                BackendFailedSource(
                    identity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS),
                    BackendForecastFailureReason.CIRCUIT_OPEN,
                ),
            ),
        )
    }

    @Test
    fun refusesDirectOnlyForecastWhenNoValidatedSeventyTwoHourBaselineExists() = runBlocking {
        val outcomes = successfulOutcomes().toMutableMap()
        ModelFamily.entries
            .filter { it != ModelFamily.UNKNOWN }
            .forEach { family ->
                outcomes[identity(ForecastProvider.OPEN_METEO, family)] =
                    failure(
                        ForecastProvider.OPEN_METEO,
                        family,
                        ServerForecastAdapterFailureReason.IO,
                    )
            }

        val result = orchestrator(FakeSources(outcomes)).forecast(target, requestedAt)

        val unavailable = assertIs<BackendForecastResult.Unavailable>(result)
        assertEquals(
            setOf(
                identity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS),
                identity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS),
                identity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON),
            ),
            unavailable.successfulCrossChecks.toSet(),
        )
        assertEquals(3, unavailable.failedSources.size)
    }

    @Test
    fun cachesOnlySuccessfulPrivacySafeForecastLoads() = runBlocking {
        val sources = FakeSources(successfulOutcomes())
        val orchestrator = orchestrator(sources)

        assertIs<BackendForecastResult.Available>(orchestrator.forecast(target, requestedAt))
        assertIs<BackendForecastResult.Available>(orchestrator.forecast(target, requestedAt))

        assertEquals(6, sources.attempts.size)
    }

    @Test
    fun unavailableLoadIsNotCached() = runBlocking {
        val sources = FakeSources(
            outcomes = buildMap {
                putAll(successfulOutcomes())
                ModelFamily.entries
                    .filter { it != ModelFamily.UNKNOWN }
                    .forEach { family ->
                        put(
                            identity(ForecastProvider.OPEN_METEO, family),
                            failure(
                                ForecastProvider.OPEN_METEO,
                                family,
                                ServerForecastAdapterFailureReason.IO,
                            ),
                        )
                    }
            }.toMutableMap(),
        )
        val orchestrator = orchestrator(sources)

        assertIs<BackendForecastResult.Unavailable>(orchestrator.forecast(target, requestedAt))
        assertIs<BackendForecastResult.Unavailable>(orchestrator.forecast(target, requestedAt))

        assertEquals(12, sources.attempts.size)
    }

    @Test
    fun coroutineCancellationPropagatesInsteadOfBecomingProviderFailure() = runBlocking {
        val sources = FakeSources(successfulOutcomes()).apply {
            throwCancellation = true
        }

        assertFailsWith<CancellationException> {
            orchestrator(sources).forecast(target, requestedAt)
        }
    }

    private fun orchestrator(sources: BackendForecastSources): BackendForecastOrchestrator =
        BackendForecastOrchestrator(
            sources = sources,
            cache = SingleFlightForecastGatewayCache(
                ttl = Duration.ofMinutes(10),
                maxEntries = 8,
                clock = Clock.fixed(requestedAt, ZoneOffset.UTC),
            ),
        )

    private fun successfulOutcomes(
        noaaDirectTemperature: Double = 10.0,
        ecmwfDirectTemperature: Double = 20.0,
        dwdDirectTemperature: Double = 30.0,
    ): MutableMap<ForecastSourceIdentity, ServerForecastAdapterResult> =
        mutableMapOf(
            identity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS) to
                success(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    noaaDirectTemperature,
                    listOf(horizonStart),
                    Instant.parse("2026-09-25T00:00:00Z"),
                ),
            identity(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS) to
                success(
                    ForecastProvider.OPEN_METEO,
                    ModelFamily.NOAA_GFS,
                    10.0,
                    horizonTimes(),
                    null,
                ),
            identity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS) to
                success(
                    ForecastProvider.ECMWF_OPEN_DATA,
                    ModelFamily.ECMWF_IFS,
                    ecmwfDirectTemperature,
                    listOf(horizonStart.plus(Duration.ofHours(2))),
                    Instant.parse("2026-09-25T00:00:00Z"),
                ),
            identity(ForecastProvider.OPEN_METEO, ModelFamily.ECMWF_IFS) to
                success(
                    ForecastProvider.OPEN_METEO,
                    ModelFamily.ECMWF_IFS,
                    20.0,
                    horizonTimes(),
                    null,
                ),
            identity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON) to
                success(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    dwdDirectTemperature,
                    listOf(horizonStart),
                    Instant.parse("2026-09-25T00:00:00Z"),
                ),
            identity(ForecastProvider.OPEN_METEO, ModelFamily.DWD_ICON) to
                success(
                    ForecastProvider.OPEN_METEO,
                    ModelFamily.DWD_ICON,
                    30.0,
                    horizonTimes(),
                    null,
                ),
        )

    private fun success(
        provider: ForecastProvider,
        family: ModelFamily,
        temperature: Double,
        times: List<Instant>,
        modelRun: Instant?,
    ): ServerForecastAdapterResult.Success =
        ServerForecastAdapterResult.Success(
            SourceForecast(
                origin = ForecastOrigin(
                    provider = provider,
                    modelFamily = family,
                    modelRun = modelRun,
                    generatedAt = requestedAt,
                ),
                location = location(),
                hourly = times.map { time ->
                    HourlyWeatherPoint(
                        time = time,
                        temperatureC = temperature,
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
                    )
                },
            ),
        )

    private fun failure(
        provider: ForecastProvider,
        family: ModelFamily,
        reason: ServerForecastAdapterFailureReason,
    ): ServerForecastAdapterResult.Failure =
        ServerForecastAdapterResult.Failure(
            provider = provider,
            modelFamily = family,
            reason = reason,
        )

    private fun horizonTimes(): List<Instant> =
        List(72) { index -> horizonStart.plus(Duration.ofHours(index.toLong())) }

    private fun location(): ForecastLocation =
        ForecastLocation(
            latitude = target.coordinate.latitude,
            longitude = target.coordinate.longitude,
            elevationMeters = target.elevationMeters,
            timeZoneId = target.timeZoneId,
        )

    private fun identity(
        provider: ForecastProvider,
        family: ModelFamily,
    ): ForecastSourceIdentity =
        ForecastSourceIdentity(provider, family)

    private inner class FakeSources(
        private val outcomes: MutableMap<ForecastSourceIdentity, ServerForecastAdapterResult>,
    ) : BackendForecastSources {
        val attempts = mutableListOf<ForecastSourceIdentity>()
        var noaaRun: Instant? = null
        var noaaHour: Int? = null
        var ecmwfRun: Instant? = null
        var ecmwfHour: Int? = null
        var dwdRun: Instant? = null
        var dwdHour: Int? = null
        var throwCancellation: Boolean = false

        override suspend fun openMeteo(
            modelFamily: ModelFamily,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): ServerForecastAdapterResult =
            outcome(identity(ForecastProvider.OPEN_METEO, modelFamily))

        override suspend fun noaa(
            modelRun: Instant,
            forecastHour: Int,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): ServerForecastAdapterResult {
            noaaRun = modelRun
            noaaHour = forecastHour
            return outcome(identity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS))
        }

        override suspend fun ecmwf(
            modelRun: Instant,
            forecastHour: Int,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): ServerForecastAdapterResult {
            ecmwfRun = modelRun
            ecmwfHour = forecastHour
            return outcome(identity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS))
        }

        override suspend fun dwd(
            modelRun: Instant,
            forecastHour: Int,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): ServerForecastAdapterResult {
            dwdRun = modelRun
            dwdHour = forecastHour
            return outcome(identity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON))
        }

        private fun outcome(identity: ForecastSourceIdentity): ServerForecastAdapterResult {
            attempts += identity
            if (throwCancellation) {
                throw CancellationException("cancelled")
            }
            return requireNotNull(outcomes[identity]) {
                "No fake outcome for $identity"
            }
        }
    }
}
