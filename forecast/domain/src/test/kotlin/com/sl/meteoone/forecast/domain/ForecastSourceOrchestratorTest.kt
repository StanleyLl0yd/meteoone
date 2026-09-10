package com.sl.meteoone.forecast.domain

import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ForecastSourceOrchestratorTest {
    private val orchestrator = ForecastSourceOrchestrator()
    private val time = Instant.parse("2026-09-09T12:00:00Z")
    private val location = ForecastLocation(
        latitude = 59.94,
        longitude = 30.31,
        elevationMeters = 10,
        timeZoneId = "Europe/Moscow",
    )

    @Test
    fun preservesForecastWhenSomeSourcesFail() {
        val noaa = identity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS)
        val ecmwf = identity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS)
        val dwd = identity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON)

        val result = orchestrator.combine(
            listOf(
                ForecastSourceResult.Success(source(noaa, temperature = 10.0)),
                ForecastSourceResult.Failure(ecmwf),
                ForecastSourceResult.Failure(dwd),
            ),
        )

        val available = assertIs<ForecastOrchestrationResult.Available>(result)
        assertEquals(10.0, available.forecast.hourly.single().weather.temperatureC)
        assertEquals(1, available.forecast.hourly.single().providerCount)
        assertEquals(1, available.forecast.hourly.single().independentEvidenceCount)
        assertEquals(listOf(noaa), available.successfulSources)
        assertEquals(listOf(ecmwf, dwd), available.failedSources)
    }

    @Test
    fun returnsUnavailableWhenEverySourceFails() {
        val noaa = identity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS)
        val ecmwf = identity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS)
        val dwd = identity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON)

        val result = orchestrator.combine(
            listOf(
                ForecastSourceResult.Failure(noaa),
                ForecastSourceResult.Failure(ecmwf),
                ForecastSourceResult.Failure(dwd),
            ),
        )

        val unavailable = assertIs<ForecastOrchestrationResult.Unavailable>(result)
        assertEquals(listOf(noaa, ecmwf, dwd), unavailable.failedSources)
    }

    @Test
    fun alternateDeliveryOfSameModelRemainsOneFusionVote() {
        val directGfs = identity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS)
        val fallbackGfs = identity(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS)
        val directIcon = identity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON)

        val result = orchestrator.combine(
            listOf(
                ForecastSourceResult.Success(source(directGfs, temperature = 8.0)),
                ForecastSourceResult.Success(source(fallbackGfs, temperature = 12.0)),
                ForecastSourceResult.Success(source(directIcon, temperature = 20.0)),
            ),
        )

        val available = assertIs<ForecastOrchestrationResult.Available>(result)
        val hour = available.forecast.hourly.single()
        assertEquals(15.0, hour.weather.temperatureC)
        assertEquals(3, hour.providerCount)
        assertEquals(2, hour.independentEvidenceCount)
        assertEquals(listOf(directGfs, fallbackGfs, directIcon), available.successfulSources)
        assertEquals(emptyList(), available.failedSources)
    }

    @Test
    fun rejectsDuplicateProviderAndModelAttemptResults() {
        val noaa = identity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS)

        assertFailsWith<IllegalArgumentException> {
            orchestrator.combine(
                listOf(
                    ForecastSourceResult.Success(source(noaa, temperature = 10.0)),
                    ForecastSourceResult.Failure(noaa),
                ),
            )
        }
    }

    @Test
    fun rejectsEmptyAttemptSet() {
        assertFailsWith<IllegalArgumentException> {
            orchestrator.combine(emptyList())
        }
    }

    private fun identity(
        provider: ForecastProvider,
        modelFamily: ModelFamily,
    ) = ForecastSourceIdentity(provider, modelFamily)

    private fun source(
        identity: ForecastSourceIdentity,
        temperature: Double,
    ) = SourceForecast(
        origin = ForecastOrigin(
            provider = identity.provider,
            modelFamily = identity.modelFamily,
            modelRun = time.minusSeconds(6 * 60 * 60),
            generatedAt = time.minusSeconds(60),
        ),
        location = location,
        hourly = listOf(
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
            ),
        ),
    )
}
