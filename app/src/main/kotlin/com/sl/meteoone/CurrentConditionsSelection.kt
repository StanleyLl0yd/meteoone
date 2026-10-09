package com.sl.meteoone

import com.sl.meteoone.core.model.FusedHourlyForecast
import java.time.Duration
import java.time.Instant

private val MAX_CURRENT_HOUR_OFFSET: Duration = Duration.ofHours(1)

/** Selects a cached forecast hour valid for now without presenting old hours as current. */
internal fun selectCurrentForecastHour(
    hourly: List<FusedHourlyForecast>,
    now: Instant,
): FusedHourlyForecast? {
    val current = hourly.lastOrNull { !it.weather.time.isAfter(now) }
    if (current != null && Duration.between(current.weather.time, now) < MAX_CURRENT_HOUR_OFFSET) {
        return current
    }
    val upcoming = hourly.firstOrNull { it.weather.time.isAfter(now) }
    return upcoming?.takeIf {
        Duration.between(now, it.weather.time) <= MAX_CURRENT_HOUR_OFFSET
    }
}
