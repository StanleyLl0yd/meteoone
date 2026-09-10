package com.sl.meteoone.forecast.data.grib

import java.time.Instant

enum class GribForecastParameter {
    TEMPERATURE_2M,
    DEW_POINT_2M,
    RELATIVE_HUMIDITY_2M,
    PRESSURE_MEAN_SEA_LEVEL,
    WIND_U_10M,
    WIND_V_10M,
    WIND_GUST_10M,
    PRECIPITATION_ACCUMULATION,
    TOTAL_CLOUD_COVER,
    VISIBILITY,
}

enum class GribValueUnit {
    KELVIN,
    PASCAL,
    METRES_PER_SECOND,
    METRES,
    KILOGRAMS_PER_SQUARE_METRE,
    PERCENT,
}

data class DecodedGribField(
    val parameter: GribForecastParameter,
    val value: Double,
    val unit: GribValueUnit,
    val validTime: Instant,
    val intervalStart: Instant? = null,
) {
    init {
        require(value.isFinite()) { "Decoded GRIB value must be finite" }
        require(intervalStart == null || !intervalStart.isAfter(validTime)) {
            "GRIB accumulation interval must not start after its valid time"
        }
    }
}

fun interface GribFieldDecoder {
    fun decode(payload: ByteArray): List<DecodedGribField>
}
