package com.sl.meteoone.core.location

import com.sl.meteoone.core.model.ForecastCoordinate

sealed interface ForecastLocationTarget {
    data object CurrentDevice : ForecastLocationTarget

    data class Manual private constructor(
        val coordinate: ForecastCoordinate,
    ) : ForecastLocationTarget {
        companion object {
            fun fromCoordinates(
                latitude: Double,
                longitude: Double,
            ): Manual =
                Manual(
                    ForecastCoordinateNormalizer.normalize(
                        latitude = latitude,
                        longitude = longitude,
                    ),
                )
        }
    }
}
