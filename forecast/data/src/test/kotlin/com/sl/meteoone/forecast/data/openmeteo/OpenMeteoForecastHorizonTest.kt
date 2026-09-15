package com.sl.meteoone.forecast.data.openmeteo

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenMeteoForecastHorizonTest {
    private val coordinate = ForecastCoordinate(latitude = 59.9, longitude = 30.3)
    private val generatedAt = Instant.parse("2026-09-10T12:30:00Z")
    private val request = OpenMeteoForecastRequestPlanner.plan(
        model = OpenMeteoModel.NOAA_GFS_GLOBAL,
        coordinate = coordinate,
        generatedAt = generatedAt,
    )
    private val location = ForecastLocation(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        elevationMeters = null,
        timeZoneId = "Europe/Moscow",
    )

    @Test
    fun rejectsHourlyResponseShiftedOutsideTheRequestedAbsoluteHorizon() {
        val error = assertFailsWith<IllegalArgumentException> {
            OpenMeteoForecastMapper().map(
                request = request,
                generatedAt = generatedAt,
                location = location,
                payload = minimalPayload(request.startHour.plusSeconds(3600).epochSecond),
            )
        }

        assertEquals("Open-Meteo timestamps must match the requested 72-hour horizon", error.message)
    }

    @Test
    fun rejectsGenerationTimeThatDoesNotMatchRequestHorizonProvenance() {
        val error = assertFailsWith<IllegalArgumentException> {
            OpenMeteoForecastMapper().map(
                request = request,
                generatedAt = generatedAt.plusSeconds(1),
                location = location,
                payload = minimalPayload(request.startHour.epochSecond),
            )
        }

        assertEquals(
            "Open-Meteo mapping generation time must match the request horizon provenance",
            error.message,
        )
    }

    private fun minimalPayload(firstEpochSecond: Long): String {
        val times = List(72) { index -> firstEpochSecond + index * 3600L }.joinToString(",")
        val temperatures = List(72) { "10.0" }.joinToString(",")
        return """
            {
              "latitude": 59.9,
              "longitude": 30.3,
              "utc_offset_seconds": 0,
              "hourly_units": {
                "time": "unixtime",
                "temperature_2m": "°C"
              },
              "hourly": {
                "time": [$times],
                "temperature_2m": [$temperatures]
              }
            }
        """.trimIndent()
    }
}
