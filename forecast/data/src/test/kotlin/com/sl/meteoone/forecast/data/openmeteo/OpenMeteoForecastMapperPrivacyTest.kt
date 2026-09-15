package com.sl.meteoone.forecast.data.openmeteo

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OpenMeteoForecastMapperPrivacyTest {
    private val coordinate = ForecastCoordinate(latitude = 59.9, longitude = 30.3)

    @Test
    fun malformedJsonDoesNotExposePayloadInException() {
        val secret = "meteoone-sensitive-json-sentinel"
        val error = assertFailsWith<IllegalArgumentException> {
            OpenMeteoForecastMapper().map(
                request = OpenMeteoForecastRequestPlanner.plan(
                    model = OpenMeteoModel.ECMWF_IFS,
                    coordinate = coordinate,
                ),
                generatedAt = Instant.parse("2026-09-15T00:00:00Z"),
                location = ForecastLocation(
                    latitude = coordinate.latitude,
                    longitude = coordinate.longitude,
                    elevationMeters = null,
                    timeZoneId = "Europe/Moscow",
                ),
                payload = """{"secret":"$secret","broken":[}""",
            )
        }

        assertEquals("Open-Meteo response is not valid JSON", error.message)
        assertFalse(error.message.orEmpty().contains(secret))
        assertNull(error.cause)
    }
}
