package com.sl.meteoone.forecast.data.openmeteo

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.WeatherCondition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OpenMeteoForecastMapperTest {
    private val mapper = OpenMeteoForecastMapper()
    private val coordinate = ForecastCoordinate(latitude = 59.9, longitude = 30.3)
    private val location = ForecastLocation(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        elevationMeters = null,
        timeZoneId = "Europe/Moscow",
    )
    private val generatedAt = Instant.parse("2026-09-10T12:30:00Z")
    private val firstEpochSecond = Instant.parse("2026-09-10T13:00:00Z").epochSecond

    @Test
    fun mapsValidatedHourlyResponseToCanonicalForecast() {
        val forecast = mapper.map(
            request = request(OpenMeteoModel.NOAA_GFS_GLOBAL),
            generatedAt = generatedAt,
            location = location,
            payload = payload(
                values = mapOf(
                    "temperature_2m" to "10.0",
                    "apparent_temperature" to "8.0",
                    "dew_point_2m" to "5.0",
                    "relative_humidity_2m" to "70.0",
                    "pressure_msl" to "1013.2",
                    "wind_speed_10m" to "3.0",
                    "wind_gusts_10m" to "6.0",
                    "wind_direction_10m" to "360.0",
                    "precipitation" to "1.2",
                    "cloud_cover" to "45.0",
                    "visibility" to "9000.0",
                    "weather_code" to "65",
                ),
            ),
        )

        assertEquals(ForecastProvider.OPEN_METEO, forecast.origin.provider)
        assertEquals(ModelFamily.NOAA_GFS, forecast.origin.modelFamily)
        assertNull(forecast.origin.modelRun)
        assertEquals(generatedAt, forecast.origin.generatedAt)
        assertEquals(location, forecast.location)
        assertEquals(72, forecast.hourly.size)

        val first = forecast.hourly.first()
        assertEquals(Instant.ofEpochSecond(firstEpochSecond), first.time)
        assertEquals(10.0, first.temperatureC)
        assertEquals(8.0, first.feelsLikeC)
        assertEquals(5.0, first.dewPointC)
        assertEquals(70.0, first.humidityPercent)
        assertEquals(1013.2, first.pressureSeaLevelHpa)
        assertEquals(3.0, first.windSpeedMps)
        assertEquals(6.0, first.windGustMps)
        assertEquals(0.0, first.windDirectionDegrees)
        assertEquals(1.2, first.precipitationMm)
        assertNull(first.precipitationProbabilityPercent)
        assertEquals(45.0, first.cloudCoverPercent)
        assertEquals(9000.0, first.visibilityMeters)
        assertEquals(WeatherCondition.HEAVY_RAIN, first.condition)
        assertNull(first.windGustInterval)
        assertEquals(
            Instant.ofEpochSecond(firstEpochSecond - 3600),
            first.precipitationInterval?.start,
        )
        assertEquals(first.time, first.precipitationInterval?.end)
    }

    @Test
    fun preservesExplicitModelFamilyForEveryFallbackModel() {
        val expected = mapOf(
            OpenMeteoModel.ECMWF_IFS to ModelFamily.ECMWF_IFS,
            OpenMeteoModel.DWD_ICON_GLOBAL to ModelFamily.DWD_ICON,
            OpenMeteoModel.NOAA_GFS_GLOBAL to ModelFamily.NOAA_GFS,
        )

        expected.forEach { (model, modelFamily) ->
            val forecast = mapper.map(
                request = request(model),
                generatedAt = generatedAt,
                location = location,
                payload = payload(),
            )
            assertEquals(ForecastProvider.OPEN_METEO, forecast.origin.provider)
            assertEquals(modelFamily, forecast.origin.modelFamily)
            assertNull(forecast.origin.modelRun)
        }
    }

    @Test
    fun acceptsSelectedModelSuffixAndMissingOptionalSeries() {
        val suffixed = payload().replace(
            "\"temperature_2m\":[",
            "\"temperature_2m_ecmwf_ifs\":[",
        )
        val forecast = mapper.map(
            request = request(OpenMeteoModel.ECMWF_IFS),
            generatedAt = generatedAt,
            location = location,
            payload = suffixed,
        )
        assertEquals(10.0, forecast.hourly.first().temperatureC)

        val withoutVisibility = mapper.map(
            request = request(OpenMeteoModel.DWD_ICON_GLOBAL),
            generatedAt = generatedAt,
            location = location,
            payload = payload(omitFields = setOf("visibility")),
        )
        assertNull(withoutVisibility.hourly.first().visibilityMeters)
    }

    @Test
    fun preservesMissingValuesWithoutFabricatingFields() {
        val forecast = mapper.map(
            request = request(OpenMeteoModel.DWD_ICON_GLOBAL),
            generatedAt = generatedAt,
            location = location,
            payload = payload(
                values = OPEN_METEO_HOURLY_FIELDS.associateWith { "null" },
            ),
        )

        val first = forecast.hourly.first()
        assertNull(first.temperatureC)
        assertNull(first.feelsLikeC)
        assertNull(first.dewPointC)
        assertNull(first.humidityPercent)
        assertNull(first.pressureSeaLevelHpa)
        assertNull(first.windSpeedMps)
        assertNull(first.windGustMps)
        assertNull(first.windDirectionDegrees)
        assertNull(first.precipitationMm)
        assertNull(first.precipitationProbabilityPercent)
        assertNull(first.cloudCoverPercent)
        assertNull(first.visibilityMeters)
        assertEquals(WeatherCondition.UNKNOWN, first.condition)
        assertNull(first.precipitationInterval)
    }

    @Test
    fun mapsRepresentativeWmoCodesToCoarseDomainConditions() {
        val expected = mapOf(
            0 to WeatherCondition.CLEAR,
            2 to WeatherCondition.PARTLY_CLOUDY,
            3 to WeatherCondition.CLOUDY,
            45 to WeatherCondition.FOG,
            61 to WeatherCondition.RAIN,
            65 to WeatherCondition.HEAVY_RAIN,
            66 to WeatherCondition.SLEET,
            75 to WeatherCondition.SNOW,
            95 to WeatherCondition.THUNDERSTORM,
            47 to WeatherCondition.UNKNOWN,
        )

        expected.forEach { (code, condition) ->
            val forecast = mapper.map(
                request = request(OpenMeteoModel.ECMWF_IFS),
                generatedAt = generatedAt,
                location = location,
                payload = payload(values = mapOf("weather_code" to code.toString())),
            )
            assertEquals(condition, forecast.hourly.first().condition)
        }
    }

    @Test
    fun rejectsWrongArrayLengthUnitsTimezoneTimestampCadenceAndQuotedNumbers() {
        assertFailsWith<IllegalArgumentException> {
            map(payload = payload(temperatureCount = 71))
        }
        assertFailsWith<IllegalArgumentException> {
            map(payload = payload(unitOverrides = mapOf("pressure_msl" to "Pa")))
        }
        assertFailsWith<IllegalArgumentException> {
            map(payload = payload(utcOffsetSeconds = 3600))
        }
        assertFailsWith<IllegalArgumentException> {
            map(payload = payload(timeStepSeconds = 7200))
        }
        assertFailsWith<IllegalArgumentException> {
            map(payload = payload(values = mapOf("temperature_2m" to "\"10.0\"")))
        }
    }

    @Test
    fun rejectsMissingTemperatureWrongModelSuffixLocationMismatchAndInvalidValues() {
        assertFailsWith<IllegalArgumentException> {
            map(payload = payload(omitFields = setOf("temperature_2m")))
        }
        assertFailsWith<IllegalArgumentException> {
            map(
                payload = payload().replace(
                    "\"temperature_2m\":[",
                    "\"temperature_2m_icon_global\":[",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            mapper.map(
                request = request(OpenMeteoModel.ECMWF_IFS),
                generatedAt = generatedAt,
                location = location.copy(latitude = 60.0),
                payload = payload(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            map(payload = payload(values = mapOf("temperature_2m" to "500.0")))
        }
        assertFailsWith<IllegalArgumentException> {
            map(payload = " ".repeat(512 * 1024 + 1))
        }
    }

    private fun map(payload: String) = mapper.map(
        request = request(OpenMeteoModel.ECMWF_IFS),
        generatedAt = generatedAt,
        location = location,
        payload = payload,
    )

    private fun request(model: OpenMeteoModel) = OpenMeteoForecastRequestPlanner.plan(
        model = model,
        coordinate = coordinate,
    )

    private fun payload(
        values: Map<String, String> = emptyMap(),
        unitOverrides: Map<String, String> = emptyMap(),
        omitFields: Set<String> = emptySet(),
        temperatureCount: Int = 72,
        utcOffsetSeconds: Int = 0,
        timeStepSeconds: Long = 3600,
    ): String {
        val defaults = mapOf(
            "temperature_2m" to "10.0",
            "apparent_temperature" to "9.0",
            "dew_point_2m" to "5.0",
            "relative_humidity_2m" to "70.0",
            "pressure_msl" to "1013.0",
            "wind_speed_10m" to "3.0",
            "wind_gusts_10m" to "6.0",
            "wind_direction_10m" to "180.0",
            "precipitation" to "0.0",
            "cloud_cover" to "40.0",
            "visibility" to "10000.0",
            "weather_code" to "0",
        )
        val units = mapOf(
            "time" to "unixtime",
            "temperature_2m" to "°C",
            "apparent_temperature" to "°C",
            "dew_point_2m" to "°C",
            "relative_humidity_2m" to "%",
            "pressure_msl" to "hPa",
            "wind_speed_10m" to "m/s",
            "wind_gusts_10m" to "m/s",
            "wind_direction_10m" to "°",
            "precipitation" to "mm",
            "cloud_cover" to "%",
            "visibility" to "m",
            "weather_code" to "wmo code",
        ) + unitOverrides

        val times = List(72) { index -> firstEpochSecond + index * timeStepSeconds }
            .joinToString(",")
        val hourlyFields = OPEN_METEO_HOURLY_FIELDS
            .filterNot { it in omitFields }
            .joinToString(",") { field ->
                val count = if (field == "temperature_2m") temperatureCount else 72
                val value = values[field] ?: defaults.getValue(field)
                "\"$field\":[${List(count) { value }.joinToString(",")}]"
            }
        val unitFields = units.entries.joinToString(",") { (field, unit) ->
            "\"$field\":\"$unit\""
        }

        return """
            {
              "latitude": 59.9,
              "longitude": 30.3,
              "utc_offset_seconds": $utcOffsetSeconds,
              "timezone": "GMT",
              "hourly_units": {$unitFields},
              "hourly": {
                "time": [$times],
                $hourlyFields
              }
            }
        """.trimIndent()
    }
}
