package com.sl.meteoone.forecast.data.openmeteo

import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.model.WeatherCondition
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

class OpenMeteoForecastMapper {
    fun map(
        request: OpenMeteoForecastRequest,
        generatedAt: Instant,
        location: ForecastLocation,
        payload: String,
    ): SourceForecast {
        require(location.latitude == request.coordinate.latitude && location.longitude == request.coordinate.longitude) {
            "Open-Meteo forecast location must match the privacy-normalized request coordinate"
        }
        require(payload.toByteArray(Charsets.UTF_8).size <= request.maxResponseBytes) {
            "Open-Meteo response exceeds the configured byte limit"
        }

        val root = try {
            Json.parseToJsonElement(payload) as? JsonObject
                ?: throw IllegalArgumentException("Open-Meteo response must be a JSON object")
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Open-Meteo response is not valid JSON", error)
        }
        validateResponseMetadata(root)

        val units = root.objectValue("hourly_units")
        validateTimeUnit(units)
        val hourly = root.objectValue("hourly")
        val times = hourly.requiredArray("time").map { element ->
            element.numericPrimitive("hourly time").longOrNull
                ?: throw IllegalArgumentException("Open-Meteo hourly time must be an integer Unix timestamp")
        }
        require(times.size == FORECAST_HOURS) {
            "Open-Meteo response must contain exactly $FORECAST_HOURS hourly points"
        }
        require(times.zipWithNext().all { (previous, next) -> next - previous == SECONDS_PER_HOUR }) {
            "Open-Meteo timestamps must be strictly hourly"
        }

        val temperature = hourly.doubleSeries(
            name = "temperature_2m",
            model = request.model,
            units = units,
            expectedSize = times.size,
            required = true,
        )
        val apparentTemperature = hourly.doubleSeries("apparent_temperature", request.model, units, times.size)
        val dewPoint = hourly.doubleSeries("dew_point_2m", request.model, units, times.size)
        val humidity = hourly.doubleSeries("relative_humidity_2m", request.model, units, times.size)
        val pressure = hourly.doubleSeries("pressure_msl", request.model, units, times.size)
        val windSpeed = hourly.doubleSeries("wind_speed_10m", request.model, units, times.size)
        val windGust = hourly.doubleSeries("wind_gusts_10m", request.model, units, times.size)
        val windDirection = hourly.doubleSeries("wind_direction_10m", request.model, units, times.size)
        val precipitation = hourly.doubleSeries("precipitation", request.model, units, times.size)
        val cloudCover = hourly.doubleSeries("cloud_cover", request.model, units, times.size)
        val visibility = hourly.doubleSeries("visibility", request.model, units, times.size)
        val weatherCode = hourly.intSeries("weather_code", request.model, units, times.size)

        val points = times.indices.map { index ->
            val time = Instant.ofEpochSecond(times[index])
            val temperatureC = temperature[index].bounded(
                name = "temperature_2m",
                minimum = MIN_TEMPERATURE_C,
                maximum = MAX_TEMPERATURE_C,
            )
            val dewPointC = dewPoint[index].bounded(
                name = "dew_point_2m",
                minimum = MIN_DEW_POINT_C,
                maximum = MAX_DEW_POINT_C,
            )
            if (temperatureC != null && dewPointC != null) {
                require(dewPointC <= temperatureC + MAX_DEW_POINT_ABOVE_TEMPERATURE_C) {
                    "Open-Meteo dew point must not materially exceed temperature"
                }
            }

            val windGustMps = windGust[index].bounded(
                name = "wind_gusts_10m",
                minimum = 0.0,
                maximum = MAX_WIND_MPS,
            )
            val precipitationMm = precipitation[index].bounded(
                name = "precipitation",
                minimum = 0.0,
                maximum = MAX_PRECIPITATION_MM,
            )

            HourlyWeatherPoint(
                time = time,
                temperatureC = temperatureC,
                feelsLikeC = apparentTemperature[index].bounded(
                    name = "apparent_temperature",
                    minimum = MIN_APPARENT_TEMPERATURE_C,
                    maximum = MAX_APPARENT_TEMPERATURE_C,
                ),
                dewPointC = dewPointC,
                humidityPercent = humidity[index].bounded(
                    name = "relative_humidity_2m",
                    minimum = 0.0,
                    maximum = 100.0,
                ),
                pressureSeaLevelHpa = pressure[index].bounded(
                    name = "pressure_msl",
                    minimum = MIN_MSLP_HPA,
                    maximum = MAX_MSLP_HPA,
                ),
                windSpeedMps = windSpeed[index].bounded(
                    name = "wind_speed_10m",
                    minimum = 0.0,
                    maximum = MAX_WIND_MPS,
                ),
                windGustMps = windGustMps,
                windDirectionDegrees = windDirection[index]
                    .bounded(
                        name = "wind_direction_10m",
                        minimum = 0.0,
                        maximum = FULL_CIRCLE_DEGREES,
                    )
                    ?.let { if (it == FULL_CIRCLE_DEGREES) 0.0 else it },
                precipitationMm = precipitationMm,
                precipitationProbabilityPercent = null,
                cloudCoverPercent = cloudCover[index].bounded(
                    name = "cloud_cover",
                    minimum = 0.0,
                    maximum = 100.0,
                ),
                visibilityMeters = visibility[index].bounded(
                    name = "visibility",
                    minimum = 0.0,
                    maximum = MAX_VISIBILITY_METRES,
                ),
                condition = mapWeatherCondition(weatherCode[index]),
                windGustInterval = windGustMps?.let {
                    ForecastInterval(
                        start = time.minusSeconds(request.model.windGustIntervalHours * SECONDS_PER_HOUR),
                        end = time,
                    )
                },
                precipitationInterval = precipitationMm?.let {
                    ForecastInterval(
                        start = time.minusSeconds(SECONDS_PER_HOUR),
                        end = time,
                    )
                },
            )
        }

        return SourceForecast(
            origin = ForecastOrigin(
                provider = request.provider,
                modelFamily = request.modelFamily,
                modelRun = null,
                generatedAt = generatedAt,
            ),
            location = location,
            hourly = points,
        )
    }

    private fun validateResponseMetadata(root: JsonObject) {
        val latitude = root.requiredFiniteDouble("latitude")
        val longitude = root.requiredFiniteDouble("longitude")
        require(latitude in -90.0..90.0) { "Open-Meteo response latitude is out of range" }
        require(longitude in -180.0..180.0) { "Open-Meteo response longitude is out of range" }
        require(root.requiredInt("utc_offset_seconds") == 0) {
            "Open-Meteo response must use UTC timestamps"
        }
    }

    private fun validateTimeUnit(units: JsonObject) {
        val primitive = units["time"] as? JsonPrimitive
            ?: throw IllegalArgumentException("Open-Meteo response is missing the time unit")
        require(primitive.isString && primitive.content == "unixtime") {
            "Open-Meteo time unit must be unixtime"
        }
    }

    private fun JsonObject.resolveSeries(
        name: String,
        model: OpenMeteoModel,
        required: Boolean,
    ): Series? {
        val expectedKeys = setOf(name, "${name}_${model.apiId}")
        val candidates = keys.filter { it == name || it.startsWith("${name}_") }
        require(candidates.all { it in expectedKeys }) {
            "Open-Meteo $name series has unexpected model provenance"
        }
        require(candidates.size <= 1) { "Open-Meteo $name series is ambiguous" }
        val key = candidates.singleOrNull()
        if (key == null) {
            require(!required) { "Open-Meteo response is missing required array $name" }
            return null
        }
        val values = this[key] as? JsonArray
            ?: throw IllegalArgumentException("Open-Meteo response field $key must be an array")
        return Series(key = key, values = values)
    }

    private fun JsonObject.doubleSeries(
        name: String,
        model: OpenMeteoModel,
        units: JsonObject,
        expectedSize: Int,
        required: Boolean = false,
    ): List<Double?> {
        val series = resolveSeries(name, model, required)
            ?: return List(expectedSize) { null }
        validateSeriesUnit(units, name, series.key)
        require(series.values.size == expectedSize) {
            "Open-Meteo array ${series.key} has the wrong length"
        }
        return series.values.map { element -> element.optionalFiniteDouble(name) }
    }

    private fun JsonObject.intSeries(
        name: String,
        model: OpenMeteoModel,
        units: JsonObject,
        expectedSize: Int,
    ): List<Int?> {
        val series = resolveSeries(name, model, required = false)
            ?: return List(expectedSize) { null }
        validateSeriesUnit(units, name, series.key)
        require(series.values.size == expectedSize) {
            "Open-Meteo array ${series.key} has the wrong length"
        }
        return series.values.map { element ->
            if (element is JsonNull) {
                null
            } else {
                val value = element.numericPrimitive(name).intOrNull
                    ?: throw IllegalArgumentException("Open-Meteo $name values must be integers or null")
                require(value in 0..MAX_WMO_CODE) { "Open-Meteo weather code is out of range" }
                value
            }
        }
    }

    private fun validateSeriesUnit(
        units: JsonObject,
        baseName: String,
        seriesKey: String,
    ) {
        val expectedUnit = EXPECTED_UNITS.getValue(baseName)
        val candidateKeys = if (seriesKey == baseName) listOf(baseName) else listOf(seriesKey, baseName)
        val declared = candidateKeys.filter { it in units }.map { key ->
            val primitive = units[key] as? JsonPrimitive
                ?: throw IllegalArgumentException("Open-Meteo unit for $key must be a string")
            key to primitive
        }
        require(declared.isNotEmpty()) { "Open-Meteo response is missing the unit for $baseName" }
        declared.forEach { (key, primitive) ->
            require(primitive.isString && primitive.content == expectedUnit) {
                "Open-Meteo unit for $key must be $expectedUnit"
            }
        }
    }

    private fun mapWeatherCondition(code: Int?): WeatherCondition = when (code) {
        null -> WeatherCondition.UNKNOWN
        0 -> WeatherCondition.CLEAR
        1, 2 -> WeatherCondition.PARTLY_CLOUDY
        3 -> WeatherCondition.CLOUDY
        45, 48 -> WeatherCondition.FOG
        51, 53, 55, 61, 63, 80, 81 -> WeatherCondition.RAIN
        65, 82 -> WeatherCondition.HEAVY_RAIN
        56, 57, 66, 67 -> WeatherCondition.SLEET
        71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
        95, 96, 99 -> WeatherCondition.THUNDERSTORM
        else -> WeatherCondition.UNKNOWN
    }

    private fun JsonObject.objectValue(name: String): JsonObject =
        this[name] as? JsonObject
            ?: throw IllegalArgumentException("Open-Meteo response is missing object $name")

    private fun JsonObject.requiredArray(name: String): JsonArray =
        this[name] as? JsonArray
            ?: throw IllegalArgumentException("Open-Meteo response is missing array $name")

    private fun JsonObject.requiredFiniteDouble(name: String): Double {
        val element = this[name]
            ?: throw IllegalArgumentException("Open-Meteo response is missing numeric $name")
        val value = element.numericPrimitive(name).doubleOrNull
            ?: throw IllegalArgumentException("Open-Meteo $name must be numeric")
        require(value.isFinite()) { "Open-Meteo $name must be finite" }
        return value
    }

    private fun JsonObject.requiredInt(name: String): Int {
        val element = this[name]
            ?: throw IllegalArgumentException("Open-Meteo response is missing integer $name")
        return element.numericPrimitive(name).intOrNull
            ?: throw IllegalArgumentException("Open-Meteo $name must be an integer")
    }

    private fun JsonElement.optionalFiniteDouble(name: String): Double? {
        if (this is JsonNull) return null
        val value = numericPrimitive(name).doubleOrNull
            ?: throw IllegalArgumentException("Open-Meteo $name values must be numeric or null")
        require(value.isFinite()) { "Open-Meteo $name values must be finite" }
        return value
    }

    private fun JsonElement.numericPrimitive(name: String): JsonPrimitive {
        val primitive = this as? JsonPrimitive
            ?: throw IllegalArgumentException("Open-Meteo $name values must be numeric")
        require(!primitive.isString) { "Open-Meteo $name values must not be quoted numbers" }
        return primitive
    }

    private fun Double?.bounded(
        name: String,
        minimum: Double,
        maximum: Double,
    ): Double? {
        if (this == null) return null
        require(this in minimum..maximum) { "Open-Meteo $name is outside the supported range" }
        return this
    }

    private data class Series(
        val key: String,
        val values: JsonArray,
    )

    private companion object {
        const val FORECAST_HOURS = 72
        const val SECONDS_PER_HOUR = 3600L
        const val FULL_CIRCLE_DEGREES = 360.0
        const val MAX_DEW_POINT_ABOVE_TEMPERATURE_C = 0.5
        const val MIN_TEMPERATURE_C = -120.0
        const val MAX_TEMPERATURE_C = 80.0
        const val MIN_APPARENT_TEMPERATURE_C = -150.0
        const val MAX_APPARENT_TEMPERATURE_C = 100.0
        const val MIN_DEW_POINT_C = -150.0
        const val MAX_DEW_POINT_C = 80.0
        const val MIN_MSLP_HPA = 500.0
        const val MAX_MSLP_HPA = 1200.0
        const val MAX_WIND_MPS = 200.0
        const val MAX_PRECIPITATION_MM = 5000.0
        const val MAX_VISIBILITY_METRES = 1_000_000.0
        const val MAX_WMO_CODE = 999

        val EXPECTED_UNITS = mapOf(
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
        )
    }
}
