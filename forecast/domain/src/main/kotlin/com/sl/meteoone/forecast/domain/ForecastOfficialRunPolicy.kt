package com.sl.meteoone.forecast.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

const val FORECAST_HORIZON_HOURS: Int = 72
val FORECAST_HOURLY_CADENCE: Duration = Duration.ofHours(1)
private val OFFICIAL_PUBLICATION_GUARD: Duration = Duration.ofHours(7)

/**
 * Shared direct-official run policy used by Android M1 execution and the M5 backend.
 *
 * The publication guard and forecast-step rounding are intentionally centralized so moving
 * orchestration server-side cannot silently select a different model run from the accepted
 * Android behavior.
 */
object ForecastOfficialRunPolicy {
    fun selectModelRun(generatedAt: Instant): Instant {
        val eligible = generatedAt.minus(OFFICIAL_PUBLICATION_GUARD).atOffset(ZoneOffset.UTC)
        val cycleHour = eligible.hour / 6 * 6
        return eligible
            .toLocalDate()
            .atStartOfDay()
            .plusHours(cycleHour.toLong())
            .toInstant(ZoneOffset.UTC)
    }

    fun hourlyForecastHour(
        modelRun: Instant,
        generatedAt: Instant,
    ): Int = ceilForecastHour(modelRun, generatedAt)

    fun ecmwfForecastHour(
        modelRun: Instant,
        generatedAt: Instant,
    ): Int {
        val hourly = ceilForecastHour(modelRun, generatedAt)
        return ((hourly + 2) / 3) * 3
    }

    private fun ceilForecastHour(
        modelRun: Instant,
        generatedAt: Instant,
    ): Int {
        require(!generatedAt.isBefore(modelRun)) {
            "Forecast generation time must not precede the selected model run"
        }
        val elapsed = Duration.between(modelRun, generatedAt)
        val completedHours = elapsed.toHours()
        val hour = if (elapsed.minusHours(completedHours).isZero) {
            completedHours
        } else {
            completedHours + 1
        }
        require(hour in 0..FORECAST_HORIZON_HOURS) {
            "Selected direct-source cross-check hour must remain inside the forecast horizon"
        }
        return hour.toInt()
    }
}
