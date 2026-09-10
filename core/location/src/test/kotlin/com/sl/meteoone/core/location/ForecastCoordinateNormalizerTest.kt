package com.sl.meteoone.core.location

import com.sl.meteoone.core.model.ForecastCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ForecastCoordinateNormalizerTest {
    @Test
    fun roundsCoordinatesToPrivacyGrid() {
        assertEquals(
            ForecastCoordinate(latitude = 12.3, longitude = 56.8),
            ForecastCoordinateNormalizer.normalize(
                latitude = 12.34,
                longitude = 56.76,
            ),
        )
    }

    @Test
    fun appliesSameNormalizationToNegativeCoordinates() {
        assertEquals(
            ForecastCoordinate(latitude = -12.4, longitude = -56.8),
            ForecastCoordinateNormalizer.normalize(
                latitude = -12.35,
                longitude = -56.75,
            ),
        )
    }

    @Test
    fun normalizationIsIdempotent() {
        val first = ForecastCoordinateNormalizer.normalize(
            latitude = 45.67,
            longitude = 89.01,
        )

        assertEquals(
            first,
            ForecastCoordinateNormalizer.normalize(first.latitude, first.longitude),
        )
    }

    @Test
    fun preservesValidCoordinateBoundaries() {
        assertEquals(
            ForecastCoordinate(latitude = 90.0, longitude = -180.0),
            ForecastCoordinateNormalizer.normalize(
                latitude = 89.96,
                longitude = -179.96,
            ),
        )
    }

    @Test
    fun canonicalizesNegativeZero() {
        val normalized = ForecastCoordinateNormalizer.normalize(
            latitude = -0.01,
            longitude = -0.01,
        )

        assertEquals(0.0, normalized.latitude)
        assertEquals(0.0, normalized.longitude)
        assertEquals(0.0.toBits(), normalized.latitude.toBits())
        assertEquals(0.0.toBits(), normalized.longitude.toBits())
    }

    @Test
    fun rejectsNonFiniteOrOutOfRangeCoordinates() {
        val invalidCoordinates = listOf(
            Double.NaN to 0.0,
            Double.POSITIVE_INFINITY to 0.0,
            90.1 to 0.0,
            -90.1 to 0.0,
            0.0 to Double.NEGATIVE_INFINITY,
            0.0 to 180.1,
            0.0 to -180.1,
        )

        invalidCoordinates.forEach { (latitude, longitude) ->
            assertFailsWith<IllegalArgumentException> {
                ForecastCoordinateNormalizer.normalize(latitude, longitude)
            }
        }
    }
}
