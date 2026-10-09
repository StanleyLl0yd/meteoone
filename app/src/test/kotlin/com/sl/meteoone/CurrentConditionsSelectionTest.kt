package com.sl.meteoone

import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CurrentConditionsSelectionTest {
    private val start = Instant.parse("2026-10-09T12:00:00Z")
    private val hours = (0L..3L).map { offset -> point(start.plus(Duration.ofHours(offset))) }

    @Test
    fun usesTheCurrentCachedHourInsteadOfAlwaysTheFirstHour() {
        assertSame(hours[2], selectCurrentForecastHour(hours, start.plusSeconds(2 * 3600 + 42 * 60)))
        assertSame(hours[1], selectCurrentForecastHour(hours, start.plusSeconds(3600)))
    }

    @Test
    fun allowsFirstUpcomingHourOnlyWhenCurrentHourIsNotInCache() {
        assertSame(hours[0], selectCurrentForecastHour(hours, start.minus(Duration.ofMinutes(30))))
        assertNull(selectCurrentForecastHour(hours, start.minus(Duration.ofHours(2))))
    }

    @Test
    fun neverPresentsHoursOutsideForecastHorizonAsCurrent() {
        assertNull(selectCurrentForecastHour(hours, start.plus(Duration.ofHours(5))))
        assertNull(selectCurrentForecastHour(emptyList(), start))
    }

    @Test
    fun fallsBackToNearbyFuturePointWhenCacheHasAGap() {
        assertSame(
            hours[3],
            selectCurrentForecastHour(listOf(hours[0], hours[3]), start.plus(Duration.ofHours(2))),
        )
    }

    private fun point(time: Instant): FusedHourlyForecast = FusedHourlyForecast(
        weather = HourlyWeatherPoint(
            time = time,
            temperatureC = 12.0,
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
        providerCount = 1,
        independentEvidenceCount = 1,
        agreement = ModelAgreement.INSUFFICIENT,
    )
}
