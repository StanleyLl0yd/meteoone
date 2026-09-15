package com.sl.meteoone.core.model

/**
 * Privacy-safe forecast target that can survive process restarts.
 *
 * [coordinate] is already reduced to the canonical forecast grid. Raw device coordinates must be
 * normalized by `:core:location` before constructing this value.
 */
data class ForecastTarget(
    val coordinate: ForecastCoordinate,
    val elevationMeters: Int?,
    val timeZoneId: String,
) {
    init {
        require(timeZoneId.isNotBlank()) {
            "Forecast target time zone must not be blank"
        }
    }
}
