package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OfficialGribForecastMapperInvariantTest {
    private val mapper = OfficialGribForecastMapper()
    private val modelRun = Instant.parse("2026-09-10T06:00:00Z")
    private val validTime = Instant.parse("2026-09-10T09:00:00Z")
    private val location = ForecastLocation(
        latitude = 59.9311,
        longitude = 30.3609,
        elevationMeters = 10,
        timeZoneId = "Europe/Moscow",
    )

    @Test
    fun generatedAtMustNotPrecedeModelRun() {
        assertFailsWith<IllegalArgumentException> {
            mapper.map(
                provider = ForecastProvider.NOAA_NOMADS,
                modelRun = modelRun,
                generatedAt = modelRun.minusSeconds(1),
                location = location,
                fields = listOf(temperature(280.0)),
            )
        }
    }

    @Test
    fun dewPointMayOnlyExceedTemperatureWithinRoundingTolerance() {
        val accepted = mapper.map(
            provider = ForecastProvider.NOAA_NOMADS,
            modelRun = modelRun,
            generatedAt = modelRun.plusSeconds(60),
            location = location,
            fields = listOf(
                temperature(280.0),
                dewPoint(280.4),
            ),
        )
        assertEquals(100.0, accepted.hourly.single().humidityPercent)

        assertFailsWith<IllegalArgumentException> {
            mapper.map(
                provider = ForecastProvider.NOAA_NOMADS,
                modelRun = modelRun,
                generatedAt = modelRun.plusSeconds(60),
                location = location,
                fields = listOf(
                    temperature(280.0),
                    dewPoint(280.6),
                ),
            )
        }
    }

    private fun temperature(value: Double) = DecodedGribField(
        parameter = GribForecastParameter.TEMPERATURE_2M,
        value = value,
        unit = GribValueUnit.KELVIN,
        validTime = validTime,
    )

    private fun dewPoint(value: Double) = DecodedGribField(
        parameter = GribForecastParameter.DEW_POINT_2M,
        value = value,
        unit = GribValueUnit.KELVIN,
        validTime = validTime,
    )
}
