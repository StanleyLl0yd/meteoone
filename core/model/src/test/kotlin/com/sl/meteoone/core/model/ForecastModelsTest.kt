package com.sl.meteoone.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ForecastModelsTest {
    private val time = Instant.parse("2026-09-10T12:00:00Z")
    private val origin = ForecastOrigin(
        provider = ForecastProvider.NOAA_NOMADS,
        modelFamily = ModelFamily.NOAA_GFS,
        modelRun = time.minusSeconds(6 * 60 * 60),
        generatedAt = time.minusSeconds(60),
    )
    private val location = ForecastLocation(
        latitude = 59.94,
        longitude = 30.31,
        elevationMeters = 10,
        timeZoneId = "Europe/Moscow",
    )

    @Test
    fun forecastIntervalRequiresPositiveDuration() {
        val interval = ForecastInterval(
            start = time.minusSeconds(3600),
            end = time,
        )
        assertEquals(3600, interval.duration.seconds)

        assertFailsWith<IllegalArgumentException> {
            ForecastInterval(start = time, end = time)
        }
        assertFailsWith<IllegalArgumentException> {
            ForecastInterval(start = time.plusSeconds(1), end = time)
        }
    }

    @Test
    fun intervalMetadataRequiresMatchingValueAndPointEnd() {
        val oneHour = ForecastInterval(
            start = time.minusSeconds(3600),
            end = time,
        )
        point(
            precipitationMm = 1.0,
            precipitationInterval = oneHour,
            windGustMps = 9.0,
            windGustInterval = oneHour,
        )

        assertFailsWith<IllegalArgumentException> {
            point(precipitationMm = null, precipitationInterval = oneHour)
        }
        assertFailsWith<IllegalArgumentException> {
            point(windGustMps = null, windGustInterval = oneHour)
        }
        assertFailsWith<IllegalArgumentException> {
            point(
                precipitationMm = 1.0,
                precipitationInterval = ForecastInterval(
                    start = time.minusSeconds(7200),
                    end = time.minusSeconds(3600),
                ),
            )
        }
    }

    @Test
    fun sourceForecastRequiresNonEmptyStrictlyIncreasingTimeline() {
        val first = point(pointTime = time)
        val second = point(pointTime = time.plusSeconds(3600))
        val valid = SourceForecast(
            origin = origin,
            location = location,
            hourly = listOf(first, second),
        )
        assertEquals(listOf(first, second), valid.hourly)

        assertFailsWith<IllegalArgumentException> {
            SourceForecast(
                origin = origin,
                location = location,
                hourly = emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SourceForecast(
                origin = origin,
                location = location,
                hourly = listOf(first, first),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SourceForecast(
                origin = origin,
                location = location,
                hourly = listOf(second, first),
            )
        }
    }

    private fun point(
        pointTime: Instant = time,
        precipitationMm: Double? = null,
        precipitationInterval: ForecastInterval? = null,
        windGustMps: Double? = null,
        windGustInterval: ForecastInterval? = null,
    ) = HourlyWeatherPoint(
        time = pointTime,
        temperatureC = null,
        feelsLikeC = null,
        dewPointC = null,
        humidityPercent = null,
        pressureSeaLevelHpa = null,
        windSpeedMps = null,
        windGustMps = windGustMps,
        windDirectionDegrees = null,
        precipitationMm = precipitationMm,
        precipitationProbabilityPercent = null,
        cloudCoverPercent = null,
        visibilityMeters = null,
        windGustInterval = windGustInterval,
        precipitationInterval = precipitationInterval,
    )
}
