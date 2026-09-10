package com.sl.meteoone.core.location

import kotlin.math.abs
import kotlin.math.round

/**
 * A privacy-reduced coordinate suitable for forecast lookup and cache identity.
 *
 * Construction is internal so callers outside this module cannot accidentally wrap raw device
 * coordinates without applying the location normalization policy first.
 */
data class ForecastCoordinate internal constructor(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Forecast latitude must be finite and within [-90, 90]"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Forecast longitude must be finite and within [-180, 180]"
        }
        require(isOnForecastGrid(latitude) && isOnForecastGrid(longitude)) {
            "Forecast coordinates must be normalized to the 0.1 degree grid"
        }
    }

    private fun isOnForecastGrid(value: Double): Boolean {
        val scaled = value / ForecastCoordinateNormalizer.GRID_STEP_DEGREES
        return abs(scaled - round(scaled)) < GRID_EPSILON
    }

    private companion object {
        const val GRID_EPSILON = 1e-9
    }
}
