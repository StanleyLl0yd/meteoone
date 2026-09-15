package com.sl.meteoone.core.model

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ForecastCoordinateTest {
    @Test
    fun rejectsInvalidNonCanonicalOrNonNormalizedCoordinates() {
        listOf(
            Double.NaN to 0.0,
            Double.POSITIVE_INFINITY to 0.0,
            90.1 to 0.0,
            0.0 to Double.NaN,
            0.0 to 180.0,
            0.0 to 180.1,
            -0.0 to 0.0,
            0.0 to -0.0,
            (0.1 + 0.2) to 56.8,
            59.900000000000006 to 30.3,
            12.34 to 56.8,
            12.3 to 56.78,
        ).forEach { (latitude, longitude) ->
            assertFailsWith<IllegalArgumentException> {
                ForecastCoordinate(latitude, longitude)
            }
        }
    }
}
