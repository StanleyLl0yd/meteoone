package com.sl.meteoone.core.location

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
    }
}
