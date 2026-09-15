package com.sl.meteoone.forecast.data.execution

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class M1ForecastEngineCadenceTest {
    private val location = ForecastLocation(
        latitude = 59.9,
        longitude = 30.3,
        elevationMeters = null,
        timeZoneId = "Europe/Moscow",
    )
    private val generatedAt = Instant.parse("2026-09-14T12:30:00Z")

    @Test
    fun rejectsSubsecondDriftHiddenInsideWholeDurationSeconds() {
        val error = assertFailsWith<IllegalArgumentException> {
            M1ForecastEngine(NanosecondDriftExecutor()).forecast(location, generatedAt)
        }

        assertEquals("M1 baseline must use an exact hourly cadence", error.message)
    }

    private inner class NanosecondDriftExecutor : ForecastSourceExecutor {
        override fun openMeteo(
            model: OpenMeteoModel,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): SourceForecast {
            val firstTime = Instant.parse("2026-09-14T13:00:00Z")
            return SourceForecast(
                origin = ForecastOrigin(
                    provider = ForecastProvider.OPEN_METEO,
                    modelFamily = model.modelFamily,
                    modelRun = null,
                    generatedAt = generatedAt,
                ),
                location = location,
                hourly = List(72) { index ->
                    val exact = firstTime.plusSeconds(index * 3600L)
                    val time = if (index == 0) exact else exact.plusNanos(1)
                    HourlyWeatherPoint(
                        time = time,
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
                    )
                },
            )
        }

        override fun noaa(
            modelRun: Instant,
            forecastHour: Int,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): SourceForecast = error("not needed for cadence regression")

        override fun ecmwf(
            modelRun: Instant,
            forecastHour: Int,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): SourceForecast = error("not needed for cadence regression")

        override fun dwd(
            modelRun: Instant,
            forecastHour: Int,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): SourceForecast = error("not needed for cadence regression")
    }
}
