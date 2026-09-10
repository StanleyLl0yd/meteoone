package com.sl.meteoone.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ForecastModelsTest {
    private val time = Instant.parse("2026-09-10T12:00:00Z")

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

    private fun point(
        precipitationMm: Double? = null,
        precipitationInterval: ForecastInterval? = null,
        windGustMps: Double? = null,
        windGustInterval: ForecastInterval? = null,
    ) = HourlyWeatherPoint(
        time = time,
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
