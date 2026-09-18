package com.sl.meteoone

import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.WeatherCondition
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal data class DailyForecastSummary(
    val date: LocalDate,
    val minimumTemperatureC: Double?,
    val maximumTemperatureC: Double?,
    val representativeCondition: WeatherCondition,
    val representativeTime: Instant,
)

internal fun buildDailyForecastSummaries(
    items: List<FusedHourlyForecast>,
    timeZoneId: String,
    limit: Int = 3,
): List<DailyForecastSummary> {
    require(limit >= 0) { "Daily summary limit must not be negative" }
    if (limit == 0 || items.isEmpty()) return emptyList()

    val zone = ZoneId.of(timeZoneId)

    return items
        .groupBy { item -> item.weather.time.atZone(zone).toLocalDate() }
        .toSortedMap()
        .entries
        .take(limit)
        .map { (date, dayItems) ->
            val temperatures = dayItems.mapNotNull { item -> item.weather.temperatureC }
            val localNoon = date.atTime(LocalTime.NOON).atZone(zone).toInstant()
            val representative = dayItems.minWithOrNull(
                compareBy<FusedHourlyForecast> { item ->
                    Duration.between(localNoon, item.weather.time).abs()
                }.thenBy { item -> item.weather.time },
            ) ?: error("A grouped forecast day must contain at least one hourly point")

            DailyForecastSummary(
                date = date,
                minimumTemperatureC = temperatures.minOrNull(),
                maximumTemperatureC = temperatures.maxOrNull(),
                representativeCondition = representative.weather.condition,
                representativeTime = representative.weather.time,
            )
        }
}
