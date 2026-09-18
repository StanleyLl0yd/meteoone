package com.sl.meteoone.forecast.data.openmeteo

import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.model.WeatherCondition
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal class OpenMeteoSingleRunMapper {
    fun map(
        request: OpenMeteoSingleRunRequest,
        capturedAt: Instant,
        location: ForecastLocation,
        payload: String,
    ): SourceForecast {
        require(
            location.latitude == request.coordinate.latitude &&
                location.longitude == request.coordinate.longitude
        ) {
            "Open-Meteo Single Runs location must match the privacy-normalized request coordinate"
        }
        require(!capturedAt.isBefore(request.modelRun)) {
            "Open-Meteo Single Runs capture time must not precede model initialization"
        }
        require(payload.toByteArray(Charsets.UTF_8).size <= request.maxResponseBytes) {
            "Open-Meteo Single Runs response exceeds the configured byte limit"
        }

        val root = parseRoot(payload)
        validateMetadata(root)
        val units = root.objectValue("hourly_units")
        validateUnits(units)
        val hourly = root.objectValue("hourly")
        val times = hourly.requiredArray("time").map { element ->
            element.numericPrimitive("hourly time").longOrNull
                ?: throw IllegalArgumentException(
                    "Open-Meteo Single Runs hourly time must be an integer Unix timestamp",
                )
        }
        require(times.size == OPEN_METEO_SINGLE_RUN_FORECAST_HOURS) {
            "Open-Meteo Single Runs response must contain exactly " +
                "$OPEN_METEO_SINGLE_RUN_FORECAST_HOURS hourly points"
        }
        require(
            times.zipWithNext().all { (previous, next) ->
                next - previous == SECONDS_PER_HOUR
            },
        ) {
            "Open-Meteo Single Runs timestamps must be strictly hourly"
        }
        val firstLead = Duration.between(
            request.modelRun,
            Instant.ofEpochSecond(times.first()),
        )
        val lastLead = Duration.between(
            request.modelRun,
            Instant.ofEpochSecond(times.last()),
        )
        require(!firstLead.isNegative && firstLead <= Duration.ofHours(1)) {
            "Open-Meteo Single Runs first point must be run hour 0 or 1"
        }
        require(lastLead <= Duration.ofHours(72)) {
            "Open-Meteo Single Runs response exceeds the M4 72-hour verification horizon"
        }

        val temperature = hourly.doubleSeries("temperature_2m", times.size)
        val pressure = hourly.doubleSeries("pressure_msl", times.size)
        val windSpeed = hourly.doubleSeries("wind_speed_10m", times.size)
        val windDirection = hourly.doubleSeries("wind_direction_10m", times.size)
        val precipitation = hourly.doubleSeries("precipitation", times.size)

        val points = times.indices.map { index ->
            val time = Instant.ofEpochSecond(times[index])
            val temperatureC = temperature[index].bounded(
                "temperature_2m",
                MIN_TEMPERATURE_C,
                MAX_TEMPERATURE_C,
            )
            val pressureHpa = pressure[index].bounded(
                "pressure_msl",
                MIN_MSLP_HPA,
                MAX_MSLP_HPA,
            )
            val windSpeedMps = windSpeed[index].bounded(
                "wind_speed_10m",
                0.0,
                MAX_WIND_MPS,
            )
            val windDirectionDegrees = windDirection[index]
                .bounded("wind_direction_10m", 0.0, FULL_CIRCLE_DEGREES)
                ?.let { direction ->
                    if (direction == FULL_CIRCLE_DEGREES) 0.0 else direction
                }
                ?.takeUnless { windSpeedMps == 0.0 }
            val precipitationMm = precipitation[index].bounded(
                "precipitation",
                0.0,
                MAX_PRECIPITATION_MM,
            )

            HourlyWeatherPoint(
                time = time,
                temperatureC = temperatureC,
                feelsLikeC = null,
                dewPointC = null,
                humidityPercent = null,
                pressureSeaLevelHpa = pressureHpa,
                windSpeedMps = windSpeedMps,
                windGustMps = null,
                windDirectionDegrees = windDirectionDegrees,
                precipitationMm = precipitationMm,
                precipitationProbabilityPercent = null,
                cloudCoverPercent = null,
                visibilityMeters = null,
                condition = WeatherCondition.UNKNOWN,
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
                modelRun = request.modelRun,
                generatedAt = capturedAt,
            ),
            location = location,
            hourly = points,
        )
    }

    private fun parseRoot(payload: String): JsonObject = try {
        Json.parseToJsonElement(payload) as? JsonObject
            ?: throw IllegalArgumentException(
                "Open-Meteo Single Runs response must be a JSON object",
            )
    } catch (_: SerializationException) {
        throw IllegalArgumentException(
            "Open-Meteo Single Runs response is not valid JSON",
        )
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw IllegalArgumentException(
            "Open-Meteo Single Runs response is not valid JSON",
            error,
        )
    }

    private fun validateMetadata(root: JsonObject) {
        val latitude = root.requiredFiniteDouble("latitude")
        val longitude = root.requiredFiniteDouble("longitude")
        require(latitude in -90.0..90.0) {
            "Open-Meteo Single Runs response latitude is out of range"
        }
        require(longitude in -180.0..180.0) {
            "Open-Meteo Single Runs response longitude is out of range"
        }
        require(root.requiredLong("utc_offset_seconds") == 0L) {
            "Open-Meteo Single Runs response must use UTC timestamps"
        }
    }

    private fun validateUnits(units: JsonObject) {
        require(units.requiredString("time") == "unixtime") {
            "Open-Meteo Single Runs time unit must be unixtime"
        }
        EXPECTED_UNITS.forEach { (name, expected) ->
            require(units.requiredString(name) == expected) {
                "Open-Meteo Single Runs unit for $name must be $expected"
            }
        }
    }

    private fun JsonObject.doubleSeries(
        name: String,
        expectedSize: Int,
    ): List<Double?> {
        val values = requiredArray(name)
        require(values.size == expectedSize) {
            "Open-Meteo Single Runs array $name has the wrong length"
        }
        return values.map { element -> element.optionalFiniteDouble(name) }
    }

    private fun JsonObject.objectValue(name: String): JsonObject =
        this[name] as? JsonObject
            ?: throw IllegalArgumentException(
                "Open-Meteo Single Runs response is missing object $name",
            )

    private fun JsonObject.requiredArray(name: String): JsonArray =
        this[name] as? JsonArray
            ?: throw IllegalArgumentException(
                "Open-Meteo Single Runs response is missing array $name",
            )

    private fun JsonObject.requiredFiniteDouble(name: String): Double {
        val element = this[name]
            ?: throw IllegalArgumentException(
                "Open-Meteo Single Runs response is missing numeric $name",
            )
        val value = element.numericPrimitive(name).doubleOrNull
            ?: throw IllegalArgumentException(
                "Open-Meteo Single Runs $name must be numeric",
            )
        require(value.isFinite()) {
            "Open-Meteo Single Runs $name must be finite"
        }
        return value
    }

    private fun JsonObject.requiredLong(name: String): Long {
        val element = this[name]
            ?: throw IllegalArgumentException(
                "Open-Meteo Single Runs response is missing integer $name",
            )
        return element.numericPrimitive(name).longOrNull
            ?: throw IllegalArgumentException(
                "Open-Meteo Single Runs $name must be an integer",
            )
    }

    private fun JsonObject.requiredString(name: String): String {
        val primitive = this[name] as? JsonPrimitive
            ?: throw IllegalArgumentException(
                "Open-Meteo Single Runs response is missing string $name",
            )
        require(primitive.isString) {
            "Open-Meteo Single Runs $name must be a string"
        }
        return primitive.content
    }

    private fun JsonElement.optionalFiniteDouble(name: String): Double? {
        if (this is JsonNull) return null
        val value = numericPrimitive(name).doubleOrNull
            ?: throw IllegalArgumentException(
                "Open-Meteo Single Runs $name values must be numeric or null",
            )
        require(value.isFinite()) {
            "Open-Meteo Single Runs $name values must be finite"
        }
        return value
    }

    private fun JsonElement.numericPrimitive(name: String): JsonPrimitive {
        val primitive = this as? JsonPrimitive
            ?: throw IllegalArgumentException(
                "Open-Meteo Single Runs $name values must be numeric",
            )
        require(!primitive.isString) {
            "Open-Meteo Single Runs $name values must not be quoted numbers"
        }
        return primitive
    }

    private fun Double?.bounded(
        name: String,
        minimum: Double,
        maximum: Double,
    ): Double? {
        if (this == null) return null
        require(this in minimum..maximum) {
            "Open-Meteo Single Runs $name is outside the supported range"
        }
        return this
    }

    private companion object {
        const val SECONDS_PER_HOUR = 3600L
        const val FULL_CIRCLE_DEGREES = 360.0
        const val MIN_TEMPERATURE_C = -120.0
        const val MAX_TEMPERATURE_C = 80.0
        const val MIN_MSLP_HPA = 500.0
        const val MAX_MSLP_HPA = 1200.0
        const val MAX_WIND_MPS = 200.0
        const val MAX_PRECIPITATION_MM = 5000.0

        val EXPECTED_UNITS = mapOf(
            "temperature_2m" to "°C",
            "pressure_msl" to "hPa",
            "wind_speed_10m" to "m/s",
            "wind_direction_10m" to "°",
            "precipitation" to "mm",
        )
    }
}
