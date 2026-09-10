package com.sl.meteoone.core.model

import kotlin.math.abs
import kotlin.math.round

/**
 * Privacy-reduced coordinate used as forecast request and cache identity.
 *
 * The current M1 invariant is a 0.1 degree grid. Raw device coordinates do not satisfy this type
 * unless they have already been normalized by the location boundary.
 */
data class ForecastCoordinate(
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
        val scaled = value / GRID_STEP_DEGREES
        return abs(scaled - round(scaled)) < GRID_EPSILON
    }

    companion object {
        const val GRID_STEP_DEGREES = 0.1
        private const val GRID_EPSILON = 1e-9
    }
}
