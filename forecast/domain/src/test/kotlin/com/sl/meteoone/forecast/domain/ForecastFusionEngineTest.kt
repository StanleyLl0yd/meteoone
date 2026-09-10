package com.sl.meteoone.forecast.domain

import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForecastFusionEngineTest {
    private val engine = ForecastFusionEngine()
    private val time = Instant.parse("2026-09-09T12:00:00Z")
    private val location = ForecastLocation(
        latitude = 59.94,
        longitude = 30.31,
        elevationMeters = 10,
        timeZoneId = "Europe/Moscow",
    )

    @Test
    fun duplicateExposureOfSameModelCountsOnce() {
        val result = engine.fuse(
            listOf(
                source(ForecastProvider.OPEN_METEO, ModelFamily.ECMWF_IFS, temperature = 10.0),
                source(ForecastProvider.MET_NORWAY, ModelFamily.ECMWF_IFS, temperature = 14.0),
                source(ForecastProvider.UNKNOWN, ModelFamily.NOAA_GFS, temperature = 20.0),
            ),
        )

        val hour = result.hourly.single()
        assertEquals(16.0, hour.weather.temperatureC)
        assertEquals(3, hour.providerCount)
        assertEquals(2, hour.independentEvidenceCount)
    }

    @Test
    fun directAndFallbackDeliveryOfGfsStillCountsAsOneSignal() {
        val result = engine.fuse(
            listOf(
                source(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS, temperature = 8.0),
                source(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS, temperature = 12.0),
                source(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON, temperature = 20.0),
            ),
        )

        val hour = result.hourly.single()
        assertEquals(15.0, hour.weather.temperatureC)
        assertEquals(3, hour.providerCount)
        assertEquals(2, hour.independentEvidenceCount)
    }

    @Test
    fun unknownModelsRemainProviderSpecificEvidence() {
        val result = engine.fuse(
            listOf(
                source(ForecastProvider.OPEN_METEO, ModelFamily.UNKNOWN, temperature = 10.0),
                source(ForecastProvider.MET_NORWAY, ModelFamily.UNKNOWN, temperature = 12.0),
            ),
        )

        assertEquals(2, result.hourly.single().independentEvidenceCount)
    }

    @Test
    fun windDirectionUsesCircularMean() {
        val result = engine.fuse(
            listOf(
                source(
                    ForecastProvider.OPEN_METEO,
                    ModelFamily.DWD_ICON,
                    temperature = 10.0,
                    windDirection = 350.0,
                ),
                source(
                    ForecastProvider.UNKNOWN,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.5,
                    windDirection = 10.0,
                ),
            ),
        )

        val direction = assertNotNull(result.hourly.single().weather.windDirectionDegrees)
        assertTrue(direction < 1.0 || direction > 359.0)
    }

    @Test
    fun missingValuesAreNotFabricated() {
        val result = engine.fuse(
            listOf(
                source(
                    ForecastProvider.OPEN_METEO,
                    ModelFamily.DWD_ICON,
                    temperature = 10.0,
                    precipitationProbability = null,
                ),
                source(
                    ForecastProvider.UNKNOWN,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.5,
                    precipitationProbability = 40.0,
                ),
            ),
        )

        assertEquals(40.0, result.hourly.single().weather.precipitationProbabilityPercent)
        assertNull(
            engine.fuse(
                listOf(
                    source(
                        ForecastProvider.OPEN_METEO,
                        ModelFamily.DWD_ICON,
                        temperature = 10.0,
                        precipitationProbability = null,
                    ),
                ),
            ).hourly.single().weather.precipitationProbabilityPercent,
        )
    }

    @Test
    fun precipitationOnlyCombinesMatchingIntervals() {
        val oneHour = interval(hours = 1)
        val threeHours = interval(hours = 3)
        val result = engine.fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                    precipitation = 2.0,
                    precipitationInterval = oneHour,
                ),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    temperature = 10.0,
                    precipitation = 4.0,
                    precipitationInterval = oneHour,
                ),
                source(
                    ForecastProvider.ECMWF_OPEN_DATA,
                    ModelFamily.ECMWF_IFS,
                    temperature = 10.0,
                    precipitation = 9.0,
                    precipitationInterval = threeHours,
                ),
            ),
        )

        val weather = result.hourly.single().weather
        assertEquals(3.0, weather.precipitationMm)
        assertEquals(oneHour, weather.precipitationInterval)
    }

    @Test
    fun duplicateProviderDeliveryCannotWinIntervalSelection() {
        val oneHour = interval(hours = 1)
        val threeHours = interval(hours = 3)
        val result = engine.fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                    precipitation = 9.0,
                    precipitationInterval = threeHours,
                ),
                source(
                    ForecastProvider.OPEN_METEO,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                    precipitation = 11.0,
                    precipitationInterval = threeHours,
                ),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    temperature = 10.0,
                    precipitation = 2.0,
                    precipitationInterval = oneHour,
                ),
                source(
                    ForecastProvider.ECMWF_OPEN_DATA,
                    ModelFamily.ECMWF_IFS,
                    temperature = 10.0,
                    precipitation = 4.0,
                    precipitationInterval = oneHour,
                ),
            ),
        )

        val weather = result.hourly.single().weather
        assertEquals(3.0, weather.precipitationMm)
        assertEquals(oneHour, weather.precipitationInterval)
    }

    @Test
    fun shorterExplicitIntervalWinsDeterministicTie() {
        val oneHour = interval(hours = 1)
        val threeHours = interval(hours = 3)
        val result = engine.fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                    precipitation = 2.0,
                    precipitationInterval = oneHour,
                ),
                source(
                    ForecastProvider.ECMWF_OPEN_DATA,
                    ModelFamily.ECMWF_IFS,
                    temperature = 10.0,
                    precipitation = 9.0,
                    precipitationInterval = threeHours,
                ),
            ),
        )

        val weather = result.hourly.single().weather
        assertEquals(2.0, weather.precipitationMm)
        assertEquals(oneHour, weather.precipitationInterval)
    }

    @Test
    fun explicitIntervalWinsTieAgainstUnknownInterval() {
        val oneHour = interval(hours = 1)
        val result = engine.fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                    precipitation = 8.0,
                    precipitationInterval = null,
                ),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    temperature = 10.0,
                    precipitation = 2.0,
                    precipitationInterval = oneHour,
                ),
            ),
        )

        val weather = result.hourly.single().weather
        assertEquals(2.0, weather.precipitationMm)
        assertEquals(oneHour, weather.precipitationInterval)
    }

    @Test
    fun windGustOnlyCombinesMatchingIntervals() {
        val oneHour = interval(hours = 1)
        val threeHours = interval(hours = 3)
        val result = engine.fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                    windGust = 8.0,
                    windGustInterval = oneHour,
                ),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    temperature = 10.0,
                    windGust = 10.0,
                    windGustInterval = oneHour,
                ),
                source(
                    ForecastProvider.ECMWF_OPEN_DATA,
                    ModelFamily.ECMWF_IFS,
                    temperature = 10.0,
                    windGust = 20.0,
                    windGustInterval = threeHours,
                ),
            ),
        )

        val weather = result.hourly.single().weather
        assertEquals(9.0, weather.windGustMps)
        assertEquals(oneHour, weather.windGustInterval)
    }

    @Test
    fun agreementIsQualitativeAndRequiresIndependentEvidence() {
        assertEquals(ModelAgreement.INSUFFICIENT, fuseTemperatures(10.0).agreement)
        assertEquals(ModelAgreement.HIGH, fuseTemperatures(10.0, 11.0).agreement)
        assertEquals(ModelAgreement.MEDIUM, fuseTemperatures(10.0, 12.0).agreement)
        assertEquals(ModelAgreement.LOW, fuseTemperatures(10.0, 14.0).agreement)
    }

    private fun fuseTemperatures(vararg temperatures: Double) =
        engine.fuse(
            temperatures.mapIndexed { index, temperature ->
                source(
                    provider = when (index) {
                        0 -> ForecastProvider.OPEN_METEO
                        1 -> ForecastProvider.MET_NORWAY
                        else -> ForecastProvider.UNKNOWN
                    },
                    model = when (index) {
                        0 -> ModelFamily.ECMWF_IFS
                        1 -> ModelFamily.DWD_ICON
                        2 -> ModelFamily.NOAA_GFS
                        else -> ModelFamily.UNKNOWN
                    },
                    temperature = temperature,
                )
            },
        ).hourly.single()

    private fun interval(hours: Long) = ForecastInterval(
        start = time.minusSeconds(hours * 3600),
        end = time,
    )

    private fun source(
        provider: ForecastProvider,
        model: ModelFamily,
        temperature: Double,
        windDirection: Double? = null,
        precipitationProbability: Double? = null,
        precipitation: Double? = null,
        precipitationInterval: ForecastInterval? = null,
        windGust: Double? = null,
        windGustInterval: ForecastInterval? = null,
    ) = SourceForecast(
        origin = ForecastOrigin(
            provider = provider,
            modelFamily = model,
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
                windGustMps = windGust,
                windDirectionDegrees = windDirection,
                precipitationMm = precipitation,
                precipitationProbabilityPercent = precipitationProbability,
                cloudCoverPercent = null,
                visibilityMeters = null,
                windGustInterval = windGustInterval,
                precipitationInterval = precipitationInterval,
            ),
        ),
    )
}
