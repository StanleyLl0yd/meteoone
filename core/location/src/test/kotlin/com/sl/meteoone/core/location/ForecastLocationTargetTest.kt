package com.sl.meteoone.core.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ForecastLocationTargetTest {
    @Test
    fun manualTargetRetainsOnlyNormalizedCoordinate() {
        val target = ForecastLocationTarget.Manual.fromCoordinates(
            latitude = 23.44,
            longitude = 67.86,
        )

        assertEquals(
            ForecastCoordinate(latitude = 23.4, longitude = 67.9),
            target.coordinate,
        )
    }

    @Test
    fun manualTargetRejectsInvalidCoordinates() {
        assertFailsWith<IllegalArgumentException> {
            ForecastLocationTarget.Manual.fromCoordinates(
                latitude = 91.0,
                longitude = 0.0,
            )
        }
    }
}
