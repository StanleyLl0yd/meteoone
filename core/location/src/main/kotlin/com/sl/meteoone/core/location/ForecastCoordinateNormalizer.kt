package com.sl.meteoone.core.location

import com.sl.meteoone.core.model.ForecastCoordinate
import java.math.BigDecimal
import java.math.RoundingMode

object ForecastCoordinateNormalizer {
    fun normalize(
        latitude: Double,
        longitude: Double,
    ): ForecastCoordinate {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be finite and within [-90, 90]"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be finite and within [-180, 180]"
        }

        return ForecastCoordinate(
            latitude = roundToForecastGrid(latitude),
            longitude = roundToForecastGrid(longitude),
        )
    }

    private fun roundToForecastGrid(value: Double): Double {
        val step = BigDecimal.valueOf(ForecastCoordinate.GRID_STEP_DEGREES)
        val rounded = BigDecimal.valueOf(value)
            .divide(step, 0, RoundingMode.HALF_UP)
            .multiply(step)
            .toDouble()
        return if (rounded == 0.0) 0.0 else rounded
    }
}
