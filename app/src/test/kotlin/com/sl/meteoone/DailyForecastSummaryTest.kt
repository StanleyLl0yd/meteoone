package com.sl.meteoone

import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
import com.sl.meteoone.core.model.WeatherCondition
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyForecastSummaryTest {
    private val helsinki = ZoneId.of("Europe/Helsinki")

    @Test
    fun groupsForecastPointsByForecastLocalDate() {
        val items = listOf(
            fusedPoint(
                localDateTime = LocalDateTime.of(2026, 9, 17, 23, 0),
                temperatureC = 8.0,
            ),
            fusedPoint(
                localDateTime = LocalDateTime.of(2026, 9, 18, 0, 0),
                temperatureC = 7.0,
            ),
        )

        val summaries = buildDailyForecastSummaries(items, helsinki.id)

        assertEquals(2, summaries.size)
        assertEquals("2026-09-17", summaries[0].date.toString())
        assertEquals("2026-09-18", summaries[1].date.toString())
    }

    @Test
    fun keepsOnlyFirstThreeForecastLocalDays() {
        val items = (17..20).map { day ->
            fusedPoint(
                localDateTime = LocalDateTime.of(2026, 9, day, 12, 0),
                temperatureC = day.toDouble(),
            )
        }

        val summaries = buildDailyForecastSummaries(items, helsinki.id)

        assertEquals(3, summaries.size)
        assertEquals("2026-09-17", summaries.first().date.toString())
        assertEquals("2026-09-19", summaries.last().date.toString())
    }

    @Test
    fun calculatesTemperatureRangeFromPresentValuesOnly() {
        val items = listOf(
            fusedPoint(
                localDateTime = LocalDateTime.of(2026, 9, 17, 8, 0),
                temperatureC = null,
            ),
            fusedPoint(
                localDateTime = LocalDateTime.of(2026, 9, 17, 12, 0),
                temperatureC = 11.5,
            ),
            fusedPoint(
                localDateTime = LocalDateTime.of(2026, 9, 17, 18, 0),
                temperatureC = 5.0,
            ),
        )

        val summary = buildDailyForecastSummaries(items, helsinki.id).single()

        assertEquals(5.0, requireNotNull(summary.minimumTemperatureC), 0.0)
        assertEquals(11.5, requireNotNull(summary.maximumTemperatureC), 0.0)
    }

    @Test
    fun leavesTemperatureRangeUnavailableWhenDayHasNoTemperatures() {
        val summary = buildDailyForecastSummaries(
            items = listOf(
                fusedPoint(
                    localDateTime = LocalDateTime.of(2026, 9, 17, 12, 0),
                    temperatureC = null,
                ),
            ),
            timeZoneId = helsinki.id,
        ).single()

        assertNull(summary.minimumTemperatureC)
        assertNull(summary.maximumTemperatureC)
    }

    @Test
    fun choosesConditionNearestLocalNoonAndBreaksTieEarlier() {
        val items = listOf(
            fusedPoint(
                localDateTime = LocalDateTime.of(2026, 9, 17, 11, 0),
                temperatureC = 9.0,
                condition = WeatherCondition.RAIN,
            ),
            fusedPoint(
                localDateTime = LocalDateTime.of(2026, 9, 17, 13, 0),
                temperatureC = 12.0,
                condition = WeatherCondition.CLEAR,
            ),
        )

        val summary = buildDailyForecastSummaries(items, helsinki.id).single()

        assertEquals(WeatherCondition.RAIN, summary.representativeCondition)
        assertEquals(items.first().weather.time, summary.representativeTime)
    }

    private fun fusedPoint(
        localDateTime: LocalDateTime,
        temperatureC: Double?,
        condition: WeatherCondition = WeatherCondition.CLOUDY,
    ): FusedHourlyForecast = FusedHourlyForecast(
        weather = HourlyWeatherPoint(
            time = localDateTime.atZone(helsinki).toInstant(),
            temperatureC = temperatureC,
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
        providerCount = 1,
        independentEvidenceCount = 1,
        agreement = ModelAgreement.INSUFFICIENT,
    )
}
