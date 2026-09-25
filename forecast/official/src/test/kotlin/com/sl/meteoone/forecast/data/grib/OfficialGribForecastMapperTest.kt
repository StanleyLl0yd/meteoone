package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficialGribForecastMapperTest {
    private val mapper = OfficialGribForecastMapper()
    private val modelRun = Instant.parse("2026-09-10T06:00:00Z")
    private val generatedAt = Instant.parse("2026-09-10T08:00:00Z")
    private val firstTime = Instant.parse("2026-09-10T09:00:00Z")
    private val secondTime = Instant.parse("2026-09-10T12:00:00Z")
    private val location = ForecastLocation(
        latitude = 48.8566,
        longitude = 2.3522,
        elevationMeters = 35,
        timeZoneId = "Europe/Paris",
    )

    @Test
    fun mapsUnitsDerivedHumidityIntervalsAndSortedTimes() {
        val result = mapper.map(
            provider = ForecastProvider.NOAA_NOMADS,
            modelRun = modelRun,
            generatedAt = generatedAt,
            location = location,
            fields = listOf(
                field(
                    GribForecastParameter.TEMPERATURE_2M,
                    283.15,
                    GribValueUnit.KELVIN,
                    secondTime,
                ),
                field(
                    GribForecastParameter.TEMPERATURE_2M,
                    293.15,
                    GribValueUnit.KELVIN,
                    firstTime,
                ),
                field(
                    GribForecastParameter.DEW_POINT_2M,
                    283.15,
                    GribValueUnit.KELVIN,
                    firstTime,
                ),
                field(
                    GribForecastParameter.PRESSURE_MEAN_SEA_LEVEL,
                    101_325.0,
                    GribValueUnit.PASCAL,
                    firstTime,
                ),
                field(
                    GribForecastParameter.WIND_U_10M,
                    0.0,
                    GribValueUnit.METRES_PER_SECOND,
                    firstTime,
                ),
                field(
                    GribForecastParameter.WIND_V_10M,
                    -10.0,
                    GribValueUnit.METRES_PER_SECOND,
                    firstTime,
                ),
                field(
                    GribForecastParameter.WIND_GUST_10M,
                    15.0,
                    GribValueUnit.METRES_PER_SECOND,
                    firstTime,
                    intervalStart = firstTime.minusSeconds(3600),
                ),
                field(
                    GribForecastParameter.PRECIPITATION_ACCUMULATION,
                    1.5,
                    GribValueUnit.KILOGRAMS_PER_SQUARE_METRE,
                    firstTime,
                    intervalStart = firstTime.minusSeconds(3600),
                ),
                field(
                    GribForecastParameter.TOTAL_CLOUD_COVER,
                    65.0,
                    GribValueUnit.PERCENT,
                    firstTime,
                ),
                field(
                    GribForecastParameter.VISIBILITY,
                    10_000.0,
                    GribValueUnit.METRES,
                    firstTime,
                ),
            ),
        )

        assertEquals(ForecastProvider.NOAA_NOMADS, result.origin.provider)
        assertEquals(ModelFamily.NOAA_GFS, result.origin.modelFamily)
        assertEquals(modelRun, result.origin.modelRun)
        assertEquals(generatedAt, result.origin.generatedAt)
        assertEquals(location, result.location)
        assertEquals(listOf(firstTime, secondTime), result.hourly.map { it.time })

        val first = result.hourly.first()
        assertEquals(20.0, first.temperatureC!!, 1e-9)
        assertEquals(10.0, first.dewPointC!!, 1e-9)
        assertTrue(first.humidityPercent!! in 52.0..53.0)
        assertEquals(1013.25, first.pressureSeaLevelHpa!!, 1e-9)
        assertEquals(10.0, first.windSpeedMps!!, 1e-9)
        assertEquals(0.0, first.windDirectionDegrees!!, 1e-9)
        assertEquals(15.0, first.windGustMps)
        assertEquals(1.5, first.precipitationMm)
        assertEquals(65.0, first.cloudCoverPercent)
        assertEquals(10_000.0, first.visibilityMeters)
        assertEquals(firstTime.minusSeconds(3600), first.windGustInterval?.start)
        assertEquals(firstTime, first.windGustInterval?.end)
        assertEquals(firstTime.minusSeconds(3600), first.precipitationInterval?.start)
        assertEquals(firstTime, first.precipitationInterval?.end)
        assertNull(first.feelsLikeC)
        assertNull(first.precipitationProbabilityPercent)
    }

    @Test
    fun directHumidityTakesPrecedenceOverDerivedHumidity() {
        val result = mapSingleHour(
            field(GribForecastParameter.TEMPERATURE_2M, 293.15, GribValueUnit.KELVIN),
            field(GribForecastParameter.DEW_POINT_2M, 283.15, GribValueUnit.KELVIN),
            field(GribForecastParameter.RELATIVE_HUMIDITY_2M, 77.0, GribValueUnit.PERCENT),
        )

        assertEquals(77.0, result.hourly.single().humidityPercent)
    }

    @Test
    fun convertsWindComponentsToMeteorologicalCardinalDirections() {
        val cases = listOf(
            Triple(0.0, -10.0, 0.0),
            Triple(-10.0, 0.0, 90.0),
            Triple(0.0, 10.0, 180.0),
            Triple(10.0, 0.0, 270.0),
        )

        cases.forEach { (u, v, expectedDirection) ->
            val result = mapSingleHour(
                field(GribForecastParameter.WIND_U_10M, u, GribValueUnit.METRES_PER_SECOND),
                field(GribForecastParameter.WIND_V_10M, v, GribValueUnit.METRES_PER_SECOND),
            )
            val weather = result.hourly.single()
            assertEquals(10.0, weather.windSpeedMps!!, 1e-9)
            assertEquals(expectedDirection, weather.windDirectionDegrees!!, 1e-9)
        }
    }

    @Test
    fun calmWindHasSpeedButNoDirection() {
        val result = mapSingleHour(
            field(GribForecastParameter.WIND_U_10M, 0.0, GribValueUnit.METRES_PER_SECOND),
            field(GribForecastParameter.WIND_V_10M, 0.0, GribValueUnit.METRES_PER_SECOND),
        )
        val weather = result.hourly.single()
        assertEquals(0.0, weather.windSpeedMps)
        assertNull(weather.windDirectionDegrees)
    }

    @Test
    fun incompleteWindVectorDoesNotInventSpeedOrDirection() {
        val result = mapSingleHour(
            field(GribForecastParameter.WIND_U_10M, 5.0, GribValueUnit.METRES_PER_SECOND),
        )
        val weather = result.hourly.single()
        assertNull(weather.windSpeedMps)
        assertNull(weather.windDirectionDegrees)
    }

    @Test
    fun rejectsDuplicateParametersWrongUnitsAndNonOfficialProvider() {
        assertFailsWith<IllegalArgumentException> {
            mapSingleHour(
                field(GribForecastParameter.TEMPERATURE_2M, 280.0, GribValueUnit.KELVIN),
                field(GribForecastParameter.TEMPERATURE_2M, 281.0, GribValueUnit.KELVIN),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            mapSingleHour(
                field(GribForecastParameter.TEMPERATURE_2M, 280.0, GribValueUnit.PERCENT),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            mapper.map(
                provider = ForecastProvider.OPEN_METEO,
                modelRun = modelRun,
                generatedAt = generatedAt,
                location = location,
                fields = listOf(
                    field(GribForecastParameter.TEMPERATURE_2M, 280.0, GribValueUnit.KELVIN),
                ),
            )
        }
    }

    @Test
    fun rejectsInvalidPhysicalRangesAndPreRunData() {
        val invalidFields = listOf(
            field(GribForecastParameter.RELATIVE_HUMIDITY_2M, 101.0, GribValueUnit.PERCENT),
            field(GribForecastParameter.TOTAL_CLOUD_COVER, -1.0, GribValueUnit.PERCENT),
            field(GribForecastParameter.PRESSURE_MEAN_SEA_LEVEL, 1.0, GribValueUnit.PASCAL),
            field(GribForecastParameter.WIND_GUST_10M, -1.0, GribValueUnit.METRES_PER_SECOND),
            field(
                GribForecastParameter.PRECIPITATION_ACCUMULATION,
                -0.1,
                GribValueUnit.KILOGRAMS_PER_SQUARE_METRE,
            ),
            field(GribForecastParameter.VISIBILITY, -1.0, GribValueUnit.METRES),
        )
        invalidFields.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                mapSingleHour(invalid)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            mapper.map(
                provider = ForecastProvider.NOAA_NOMADS,
                modelRun = modelRun,
                generatedAt = generatedAt,
                location = location,
                fields = listOf(
                    field(
                        GribForecastParameter.TEMPERATURE_2M,
                        280.0,
                        GribValueUnit.KELVIN,
                        modelRun.minusSeconds(1),
                    ),
                ),
            )
        }
    }

    @Test
    fun decodedIntervalsMustBePositiveAndOnlyApplyToIntervalFields() {
        assertFailsWith<IllegalArgumentException> {
            field(
                GribForecastParameter.PRECIPITATION_ACCUMULATION,
                1.0,
                GribValueUnit.KILOGRAMS_PER_SQUARE_METRE,
                intervalStart = firstTime,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            field(
                GribForecastParameter.TEMPERATURE_2M,
                280.0,
                GribValueUnit.KELVIN,
                intervalStart = firstTime.minusSeconds(3600),
            )
        }
    }

    @Test
    fun allDirectOfficialProvidersPreserveExpectedModelFamily() {
        val expected = mapOf(
            ForecastProvider.NOAA_NOMADS to ModelFamily.NOAA_GFS,
            ForecastProvider.ECMWF_OPEN_DATA to ModelFamily.ECMWF_IFS,
            ForecastProvider.DWD_OPEN_DATA to ModelFamily.DWD_ICON,
        )
        expected.forEach { (provider, family) ->
            val result = mapper.map(
                provider = provider,
                modelRun = modelRun,
                generatedAt = generatedAt,
                location = location,
                fields = listOf(
                    field(GribForecastParameter.TEMPERATURE_2M, 280.0, GribValueUnit.KELVIN),
                ),
            )
            assertEquals(family, result.origin.modelFamily)
        }
    }

    private fun mapSingleHour(vararg fields: DecodedGribField) = mapper.map(
        provider = ForecastProvider.NOAA_NOMADS,
        modelRun = modelRun,
        generatedAt = generatedAt,
        location = location,
        fields = fields.toList(),
    )

    private fun field(
        parameter: GribForecastParameter,
        value: Double,
        unit: GribValueUnit,
        time: Instant = firstTime,
        intervalStart: Instant? = null,
    ) = DecodedGribField(
        parameter = parameter,
        value = value,
        unit = unit,
        validTime = time,
        intervalStart = intervalStart,
    )
}
