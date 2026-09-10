package com.sl.meteoone.core.model

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ForecastCoordinateTest {
    @Test
    fun rejectsInvalidOrNonNormalizedCoordinates() {
        listOf(
            Double.NaN to 0.0,
            Double.POSITIVE_INFINITY to 0.0,
            90.1 to 0.0,
            0.0 to Double.NaN,
            0.0 to 180.1,
            12.34 to 56.8,
            12.3 to 56.78,
        ).forEach { (latitude, longitude) ->
            assertFailsWith<IllegalArgumentException> {
                ForecastCoordinate(latitude, longitude)
            }
        }
    }
}
