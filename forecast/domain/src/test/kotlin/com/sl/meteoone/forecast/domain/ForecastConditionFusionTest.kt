package com.sl.meteoone.forecast.domain

import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.model.WeatherCondition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ForecastConditionFusionTest {
    private val engine = ForecastFusionEngine()
    private val time = Instant.parse("2026-09-10T12:00:00Z")
    private val location = ForecastLocation(
        latitude = 59.9,
        longitude = 30.3,
        elevationMeters = 10,
        timeZoneId = "Europe/Moscow",
    )

    @Test
    fun singleResolvedConditionSurvivesFusion() {
        val result = engine.fuse(
            listOf(source(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS, WeatherCondition.RAIN)),
        )

        assertEquals(WeatherCondition.RAIN, result.hourly.single().weather.condition)
    }

    @Test
    fun uniquePluralityAcrossIndependentFamiliesWins() {
        val result = engine.fuse(
            listOf(
                source(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS, WeatherCondition.RAIN),
                source(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON, WeatherCondition.RAIN),
                source(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS, WeatherCondition.CLOUDY),
            ),
        )

        assertEquals(WeatherCondition.RAIN, result.hourly.single().weather.condition)
    }

    @Test
    fun categoricalTieRemainsUnknown() {
        val result = engine.fuse(
            listOf(
                source(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS, WeatherCondition.RAIN),
                source(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON, WeatherCondition.CLOUDY),
            ),
        )

        assertEquals(WeatherCondition.UNKNOWN, result.hourly.single().weather.condition)
    }

    @Test
    fun unknownConditionDoesNotVoteAgainstResolvedEvidence() {
        val result = engine.fuse(
            listOf(
                source(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS, WeatherCondition.UNKNOWN),
                source(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON, WeatherCondition.SNOW),
            ),
        )

        assertEquals(WeatherCondition.SNOW, result.hourly.single().weather.condition)
    }

    @Test
    fun duplicateDeliveryOfOneFamilyCannotCreatePlurality() {
        val result = engine.fuse(
            listOf(
                source(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS, WeatherCondition.RAIN),
                source(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS, WeatherCondition.RAIN),
                source(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON, WeatherCondition.CLOUDY),
            ),
        )

        assertEquals(WeatherCondition.UNKNOWN, result.hourly.single().weather.condition)
    }

    @Test
    fun disagreementInsideOneFamilyMakesThatFamilyUnresolved() {
        val result = engine.fuse(
            listOf(
                source(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS, WeatherCondition.RAIN),
                source(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS, WeatherCondition.CLOUDY),
                source(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON, WeatherCondition.SNOW),
            ),
        )

        assertEquals(WeatherCondition.SNOW, result.hourly.single().weather.condition)
    }

    private fun source(
        provider: ForecastProvider,
        modelFamily: ModelFamily,
        condition: WeatherCondition,
    ) = SourceForecast(
        origin = ForecastOrigin(
            provider = provider,
            modelFamily = modelFamily,
            modelRun = time.minusSeconds(6 * 60 * 60),
            generatedAt = time.minusSeconds(60),
        ),
        location = location,
        hourly = listOf(
            HourlyWeatherPoint(
                time = time,
                temperatureC = null,
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
                condition = condition,
            ),
        ),
    )
}
