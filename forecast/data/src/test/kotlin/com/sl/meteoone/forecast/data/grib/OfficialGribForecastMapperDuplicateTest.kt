package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OfficialGribForecastMapperDuplicateTest {
    private val mapper = OfficialGribForecastMapper()
    private val modelRun = Instant.parse("2026-09-10T18:00:00Z")
    private val validTime = Instant.parse("2026-09-11T00:00:00Z")
    private val location = ForecastLocation(
        latitude = 48.75,
        longitude = 2.25,
        elevationMeters = 35,
        timeZoneId = "Europe/Paris",
    )

    @Test
    fun exactSemanticDuplicateIsCollapsed() {
        val precipitation = DecodedGribField(
            parameter = GribForecastParameter.PRECIPITATION_ACCUMULATION,
            value = 4.5,
            unit = GribValueUnit.KILOGRAMS_PER_SQUARE_METRE,
            validTime = validTime,
            intervalStart = modelRun,
        )

        val result = mapper.map(
            provider = ForecastProvider.NOAA_NOMADS,
            modelRun = modelRun,
            generatedAt = validTime,
            location = location,
            fields = listOf(precipitation, precipitation.copy()),
        )

        assertEquals(1, result.hourly.size)
        assertEquals(4.5, result.hourly.single().precipitationMm)
        assertEquals(modelRun, result.hourly.single().precipitationInterval?.start)
        assertEquals(validTime, result.hourly.single().precipitationInterval?.end)
    }

    @Test
    fun conflictingSemanticDuplicateStillFailsClosed() {
        val precipitation = DecodedGribField(
            parameter = GribForecastParameter.PRECIPITATION_ACCUMULATION,
            value = 4.5,
            unit = GribValueUnit.KILOGRAMS_PER_SQUARE_METRE,
            validTime = validTime,
            intervalStart = modelRun,
        )

        assertFailsWith<IllegalArgumentException> {
            mapper.map(
                provider = ForecastProvider.NOAA_NOMADS,
                modelRun = modelRun,
                generatedAt = validTime,
                location = location,
                fields = listOf(precipitation, precipitation.copy(value = 4.6)),
            )
        }
    }
}
