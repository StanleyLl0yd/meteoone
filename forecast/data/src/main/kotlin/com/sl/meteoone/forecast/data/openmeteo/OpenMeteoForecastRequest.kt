package com.sl.meteoone.forecast.data.openmeteo

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal const val OPEN_METEO_MAX_RESPONSE_BYTES = 512L * 1024L
private const val OPEN_METEO_FORECAST_HOURS = 72
private const val OPEN_METEO_LAST_HOUR_OFFSET = OPEN_METEO_FORECAST_HOURS - 1L
private const val OPEN_METEO_CELL_SELECTION = "land"
private val OPEN_METEO_HOUR_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
    .withZone(ZoneOffset.UTC)

internal val OPEN_METEO_HOURLY_FIELDS = listOf(
    "temperature_2m",
    "apparent_temperature",
    "dew_point_2m",
    "relative_humidity_2m",
    "pressure_msl",
    "wind_speed_10m",
    "wind_gusts_10m",
    "wind_direction_10m",
    "precipitation",
    "cloud_cover",
    "visibility",
    "weather_code",
)

internal enum class OpenMeteoModel(
    val apiId: String,
    val modelFamily: ModelFamily,
    val windGustIntervalHours: Long,
) {
    ECMWF_IFS(
        apiId = "ecmwf_ifs",
        modelFamily = ModelFamily.ECMWF_IFS,
        windGustIntervalHours = 3,
    ),
    DWD_ICON_GLOBAL(
        apiId = "icon_global",
        modelFamily = ModelFamily.DWD_ICON,
        windGustIntervalHours = 1,
    ),
    NOAA_GFS_GLOBAL(
        apiId = "ncep_gfs_global",
        modelFamily = ModelFamily.NOAA_GFS,
        windGustIntervalHours = 1,
    ),
}

internal data class OpenMeteoForecastRequest(
    val uri: URI,
    val model: OpenMeteoModel,
    val coordinate: ForecastCoordinate,
    val generatedAt: Instant,
    val maxResponseBytes: Long = OPEN_METEO_MAX_RESPONSE_BYTES,
) {
    val startHour: Instant = forecastStartHour(generatedAt)
    val endHour: Instant = startHour.plus(OPEN_METEO_LAST_HOUR_OFFSET, ChronoUnit.HOURS)

    init {
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Open-Meteo requests must use HTTPS"
        }
        require(uri.host == OPEN_METEO_HOST) {
            "Open-Meteo request host must be $OPEN_METEO_HOST"
        }
        require(uri.port == -1 || uri.port == 443) {
            "Open-Meteo requests must use the default HTTPS port"
        }
        require(uri.userInfo == null) { "Open-Meteo requests must not contain user info" }
        require(uri.path == OPEN_METEO_PATH) {
            "Open-Meteo request path must be $OPEN_METEO_PATH"
        }
        require(uri.fragment == null) { "Open-Meteo requests must not contain fragments" }
        require(parseQuery(uri.rawQuery) == expectedQuery(model, coordinate, generatedAt)) {
            "Open-Meteo request query must match model, coordinate, cell selection, horizon, fields, and units"
        }
        require(maxResponseBytes in 1..OPEN_METEO_MAX_RESPONSE_BYTES) {
            "Open-Meteo response byte limit is out of bounds"
        }
    }

    val provider: ForecastProvider
        get() = ForecastProvider.OPEN_METEO

    val modelFamily: ModelFamily
        get() = model.modelFamily

    private companion object {
        const val OPEN_METEO_HOST = "api.open-meteo.com"
        const val OPEN_METEO_PATH = "/v1/forecast"
    }
}

internal object OpenMeteoForecastRequestPlanner {
    private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"

    fun plan(
        model: OpenMeteoModel,
        coordinate: ForecastCoordinate,
        generatedAt: Instant,
    ): OpenMeteoForecastRequest {
        val query = expectedQuery(model, coordinate, generatedAt)
            .entries
            .joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

        return OpenMeteoForecastRequest(
            uri = URI.create("$BASE_URL?$query"),
            model = model,
            coordinate = coordinate,
            generatedAt = generatedAt,
        )
    }
}

private fun expectedQuery(
    model: OpenMeteoModel,
    coordinate: ForecastCoordinate,
    generatedAt: Instant,
): Map<String, String> {
    val startHour = forecastStartHour(generatedAt)
    val endHour = startHour.plus(OPEN_METEO_LAST_HOUR_OFFSET, ChronoUnit.HOURS)
    return linkedMapOf(
        "latitude" to formatCoordinate(coordinate.latitude),
        "longitude" to formatCoordinate(coordinate.longitude),
        "cell_selection" to OPEN_METEO_CELL_SELECTION,
        "models" to model.apiId,
        "hourly" to OPEN_METEO_HOURLY_FIELDS.joinToString(","),
        "start_hour" to OPEN_METEO_HOUR_FORMATTER.format(startHour),
        "end_hour" to OPEN_METEO_HOUR_FORMATTER.format(endHour),
        "timezone" to "UTC",
        "timeformat" to "unixtime",
        "temperature_unit" to "celsius",
        "wind_speed_unit" to "ms",
        "precipitation_unit" to "mm",
    )
}

private fun forecastStartHour(generatedAt: Instant): Instant {
    val truncated = generatedAt.truncatedTo(ChronoUnit.HOURS)
    return if (truncated == generatedAt) truncated else truncated.plus(1, ChronoUnit.HOURS)
}

private fun parseQuery(rawQuery: String?): Map<String, String> {
    require(!rawQuery.isNullOrBlank()) { "Open-Meteo request query must not be empty" }
    val result = linkedMapOf<String, String>()
    rawQuery.split('&').forEach { component ->
        val separator = component.indexOf('=')
        require(separator > 0) { "Open-Meteo query parameters must use key=value form" }
        val key = decode(component.substring(0, separator))
        val value = decode(component.substring(separator + 1))
        require(key !in result) { "Open-Meteo request query must not contain duplicate keys" }
        result[key] = value
    }
    return result
}

private fun formatCoordinate(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

@Suppress("DEPRECATION")
private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

@Suppress("DEPRECATION")
private fun decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
