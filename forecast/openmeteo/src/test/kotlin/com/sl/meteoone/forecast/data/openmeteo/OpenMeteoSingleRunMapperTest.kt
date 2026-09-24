package com.sl.meteoone.forecast.data.openmeteo

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OpenMeteoSingleRunMapperTest {
    private val coordinate = ForecastCoordinate(59.9, 30.3)
    private val run = Instant.parse("2026-09-15T00:00:00Z")
    private val capturedAt = Instant.parse("2026-09-18T12:00:00Z")
    private val location = ForecastLocation(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        elevationMeters = 12,
        timeZoneId = "Europe/Moscow",
    )
    private val request = OpenMeteoSingleRunRequestPlanner.plan(
        model = OpenMeteoModel.ECMWF_IFS,
        coordinate = coordinate,
        modelRun = run,
    )

    @Test
    fun mapsExactRunAndOnlyVerificationFields() {
        val forecast = OpenMeteoSingleRunMapper().map(
            request = request,
            capturedAt = capturedAt,
            location = location,
            payload = payload(firstLeadHours = 0),
        )

        assertEquals(ForecastProvider.OPEN_METEO, forecast.origin.provider)
        assertEquals(ModelFamily.ECMWF_IFS, forecast.origin.modelFamily)
        assertEquals(run, forecast.origin.modelRun)
        assertEquals(capturedAt, forecast.origin.generatedAt)
        assertEquals(location, forecast.location)
        assertEquals(72, forecast.hourly.size)

        val first = forecast.hourly.first()
        val last = forecast.hourly.last()
        assertEquals(run, first.time)
        assertEquals(run.plus(Duration.ofHours(71)), last.time)
        assertEquals(10.0, first.temperatureC)
        assertEquals(1012.5, first.pressureSeaLevelHpa)
        assertEquals(4.0, first.windSpeedMps)
        assertEquals(270.0, first.windDirectionDegrees)
        assertEquals(1.25, first.precipitationMm)
        assertEquals(first.time.minusSeconds(3600), first.precipitationInterval?.start)
        assertEquals(first.time, first.precipitationInterval?.end)
        assertNull(first.feelsLikeC)
        assertNull(first.windGustMps)
        assertNull(first.precipitationProbabilityPercent)
    }

    @Test
    fun acceptsOneThroughSeventyTwoHourRunShape() {
        val forecast = OpenMeteoSingleRunMapper().map(
            request = request,
            capturedAt = capturedAt,
            location = location,
            payload = payload(firstLeadHours = 1),
        )

        assertEquals(run.plus(Duration.ofHours(1)), forecast.hourly.first().time)
        assertEquals(run.plus(Duration.ofHours(72)), forecast.hourly.last().time)
    }

    @Test
    fun calmWindDoesNotFabricateDirectionAndNorth360NormalizesToZero() {
        val forecast = OpenMeteoSingleRunMapper().map(
            request = request,
            capturedAt = capturedAt,
            location = location,
            payload = payload(
                firstLeadHours = 0,
                windSpeed = { index -> if (index == 0) 0.0 else 4.0 },
                windDirection = { index -> if (index == 0) 180.0 else 360.0 },
            ),
        )

        assertNull(forecast.hourly[0].windDirectionDegrees)
        assertEquals(0.0, forecast.hourly[1].windDirectionDegrees)
    }

    @Test
    fun rejectsWrongCountCadenceUnitsAndHorizon() {
        val mapper = OpenMeteoSingleRunMapper()

        assertFailsWith<IllegalArgumentException> {
            mapper.map(
                request,
                capturedAt,
                location,
                payload(firstLeadHours = 0, count = 71),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            mapper.map(
                request,
                capturedAt,
                location,
                payload(
                    firstLeadHours = 0,
                    time = { index ->
                        run.epochSecond + index * 3600L + if (index >= 2) 60L else 0L
                    },
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            mapper.map(
                request,
                capturedAt,
                location,
                payload(firstLeadHours = 2),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            mapper.map(
                request,
                capturedAt,
                location,
                payload(firstLeadHours = 0).replace(
                    "\"pressure_msl\": \"hPa\"",
                    "\"pressure_msl\": \"Pa\"",
                ),
            )
        }
    }

    @Test
    fun rejectsRawLocationAndCaptureBeforeRun() {
        val mapper = OpenMeteoSingleRunMapper()
        assertFailsWith<IllegalArgumentException> {
            mapper.map(
                request = request,
                capturedAt = capturedAt,
                location = location.copy(latitude = 59.94),
                payload = payload(firstLeadHours = 0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            mapper.map(
                request = request,
                capturedAt = run.minusSeconds(1),
                location = location,
                payload = payload(firstLeadHours = 0),
            )
        }
    }

    private fun payload(
        firstLeadHours: Int,
        count: Int = 72,
        time: (Int) -> Long = { index ->
            run.epochSecond + (firstLeadHours + index) * 3600L
        },
        windSpeed: (Int) -> Double = { 4.0 },
        windDirection: (Int) -> Double = { 270.0 },
    ): String {
        val times = List(count) { index -> time(index).toString() }.joinToString(",")
        val temperature = List(count) { "10.0" }.joinToString(",")
        val pressure = List(count) { "1012.5" }.joinToString(",")
        val speed = List(count) { index -> windSpeed(index).toString() }.joinToString(",")
        val direction = List(count) { index -> windDirection(index).toString() }.joinToString(",")
        val precipitation = List(count) { "1.25" }.joinToString(",")
        return """
            {
              "latitude": 59.9,
              "longitude": 30.3,
              "utc_offset_seconds": 0,
              "hourly_units": {
                "time": "unixtime",
                "temperature_2m": "°C",
                "pressure_msl": "hPa",
                "wind_speed_10m": "m/s",
                "wind_direction_10m": "°",
                "precipitation": "mm"
              },
              "hourly": {
                "time": [$times],
                "temperature_2m": [$temperature],
                "pressure_msl": [$pressure],
                "wind_speed_10m": [$speed],
                "wind_direction_10m": [$direction],
                "precipitation": [$precipitation]
              }
            }
        """.trimIndent()
    }
}
