package com.sl.meteoone.forecast.data.openmeteo

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val OPEN_METEO_MAX_RESPONSE_BYTES = 512L * 1024L

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

enum class OpenMeteoModel(
    val apiId: String,
    val modelFamily: ModelFamily,
) {
    ECMWF_IFS(
        apiId = "ecmwf_ifs",
        modelFamily = ModelFamily.ECMWF_IFS,
    ),
    DWD_ICON_GLOBAL(
        apiId = "icon_global",
        modelFamily = ModelFamily.DWD_ICON,
    ),
    NOAA_GFS_GLOBAL(
        apiId = "ncep_gfs_global",
        modelFamily = ModelFamily.NOAA_GFS,
    ),
}

data class OpenMeteoForecastRequest(
    val uri: URI,
    val model: OpenMeteoModel,
    val coordinate: ForecastCoordinate,
    val maxResponseBytes: Long = OPEN_METEO_MAX_RESPONSE_BYTES,
) {
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

object OpenMeteoForecastRequestPlanner {
    private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
    private const val FORECAST_HOURS = 72

    fun plan(
        model: OpenMeteoModel,
        coordinate: ForecastCoordinate,
    ): OpenMeteoForecastRequest {
        val query = listOf(
            "latitude" to formatCoordinate(coordinate.latitude),
            "longitude" to formatCoordinate(coordinate.longitude),
            "models" to model.apiId,
            "hourly" to OPEN_METEO_HOURLY_FIELDS.joinToString(","),
            "forecast_hours" to FORECAST_HOURS.toString(),
            "timezone" to "UTC",
            "timeformat" to "unixtime",
            "temperature_unit" to "celsius",
            "wind_speed_unit" to "ms",
            "precipitation_unit" to "mm",
        ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }

        return OpenMeteoForecastRequest(
            uri = URI.create("$BASE_URL?$query"),
            model = model,
            coordinate = coordinate,
        )
    }

    private fun formatCoordinate(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    @Suppress("DEPRECATION")
    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
