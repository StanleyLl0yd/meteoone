package com.sl.meteoone.core.model

import java.math.BigDecimal

/**
 * Privacy-reduced coordinate used as forecast request and cache identity.
 *
 * The current M1 invariant is a canonical 0.1 degree grid. Raw device coordinates do not satisfy
 * this type unless they have already been normalized by the location boundary. Longitude uses the
 * canonical half-open interval [-180, 180), so the antimeridian has exactly one request/cache
 * identity. Canonical grid values also use positive zero and reject near-grid floating-point aliases.
 */
data class ForecastCoordinate(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Forecast latitude must be finite and within [-90, 90]"
        }
        require(longitude.isFinite() && longitude >= -180.0 && longitude < 180.0) {
            "Forecast longitude must be finite and within [-180, 180)"
        }
        require(isCanonicalGridValue(latitude) && isCanonicalGridValue(longitude)) {
            "Forecast coordinates must use the canonical 0.1 degree grid"
        }
    }

    private fun isCanonicalGridValue(value: Double): Boolean {
        if (value == 0.0 && value.toRawBits() != 0L) return false
        return BigDecimal.valueOf(value).remainder(GRID_STEP_DECIMAL).signum() == 0
    }

    companion object {
        const val GRID_STEP_DEGREES = 0.1
        private val GRID_STEP_DECIMAL = BigDecimal("0.1")
    }
}
