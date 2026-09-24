package com.sl.meteoone.forecast.data.openmeteo

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal const val OPEN_METEO_SINGLE_RUN_MAX_RESPONSE_BYTES = 256L * 1024L
val OPEN_METEO_SINGLE_RUN_MINIMUM_REQUEST_SPACING: Duration = Duration.ofSeconds(1)
internal const val OPEN_METEO_SINGLE_RUN_FORECAST_HOURS = 72

private const val SINGLE_RUN_HOST = "single-runs-api.open-meteo.com"
private const val SINGLE_RUN_PATH = "/v1/forecast"
private const val SINGLE_RUN_CELL_SELECTION = "land"
private val SINGLE_RUN_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
    .withZone(ZoneOffset.UTC)

internal val OPEN_METEO_SINGLE_RUN_HOURLY_FIELDS = listOf(
    "temperature_2m",
    "pressure_msl",
    "wind_speed_10m",
    "wind_direction_10m",
    "precipitation",
)

data class OpenMeteoSingleRunRequest(
    val uri: URI,
    val model: OpenMeteoModel,
    val coordinate: ForecastCoordinate,
    val modelRun: Instant,
    val maxResponseBytes: Long = OPEN_METEO_SINGLE_RUN_MAX_RESPONSE_BYTES,
) {
    init {
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Open-Meteo Single Runs requests must use HTTPS"
        }
        require(uri.host == SINGLE_RUN_HOST) {
            "Open-Meteo Single Runs host must be $SINGLE_RUN_HOST"
        }
        require(uri.port == -1 || uri.port == 443) {
            "Open-Meteo Single Runs requests must use the default HTTPS port"
        }
        require(uri.userInfo == null) {
            "Open-Meteo Single Runs requests must not contain user info"
        }
        require(uri.path == SINGLE_RUN_PATH) {
            "Open-Meteo Single Runs request path must be $SINGLE_RUN_PATH"
        }
        require(uri.fragment == null) {
            "Open-Meteo Single Runs requests must not contain fragments"
        }
        require(modelRun.nano == 0 && modelRun.epochSecond % 3600L == 0L) {
            "Open-Meteo Single Runs model run must be aligned to a whole UTC hour"
        }
        require(
            parseSingleRunQuery(uri.rawQuery) ==
                expectedSingleRunQuery(model, coordinate, modelRun),
        ) {
            "Open-Meteo Single Runs query must exactly match run, model, coordinate, fields and units"
        }
        require(maxResponseBytes in 1..OPEN_METEO_SINGLE_RUN_MAX_RESPONSE_BYTES) {
            "Open-Meteo Single Runs response byte limit is out of bounds"
        }
    }

    val provider: ForecastProvider
        get() = ForecastProvider.OPEN_METEO

    val modelFamily: ModelFamily
        get() = model.modelFamily
}

object OpenMeteoSingleRunRequestPlanner {
    private const val BASE_URL = "https://$SINGLE_RUN_HOST$SINGLE_RUN_PATH"

    fun plan(
        model: OpenMeteoModel,
        coordinate: ForecastCoordinate,
        modelRun: Instant,
    ): OpenMeteoSingleRunRequest {
        val query = expectedSingleRunQuery(model, coordinate, modelRun)
            .entries
            .joinToString("&") { (key, value) ->
                "${encodeSingleRun(key)}=${encodeSingleRun(value)}"
            }
        return OpenMeteoSingleRunRequest(
            uri = URI.create("$BASE_URL?$query"),
            model = model,
            coordinate = coordinate,
            modelRun = modelRun,
        )
    }
}

private fun expectedSingleRunQuery(
    model: OpenMeteoModel,
    coordinate: ForecastCoordinate,
    modelRun: Instant,
): Map<String, String> = linkedMapOf(
    "latitude" to formatSingleRunCoordinate(coordinate.latitude),
    "longitude" to formatSingleRunCoordinate(coordinate.longitude),
    "cell_selection" to SINGLE_RUN_CELL_SELECTION,
    "models" to model.apiId,
    "hourly" to OPEN_METEO_SINGLE_RUN_HOURLY_FIELDS.joinToString(","),
    "forecast_hours" to OPEN_METEO_SINGLE_RUN_FORECAST_HOURS.toString(),
    "timezone" to "UTC",
    "timeformat" to "unixtime",
    "temperature_unit" to "celsius",
    "wind_speed_unit" to "ms",
    "precipitation_unit" to "mm",
    "run" to SINGLE_RUN_FORMATTER.format(modelRun),
)

private fun parseSingleRunQuery(rawQuery: String?): Map<String, String> {
    require(!rawQuery.isNullOrBlank()) {
        "Open-Meteo Single Runs request query must not be empty"
    }
    val result = linkedMapOf<String, String>()
    rawQuery.split('&').forEach { component ->
        val separator = component.indexOf('=')
        require(separator > 0) {
            "Open-Meteo Single Runs query parameters must use key=value form"
        }
        val key = decodeSingleRun(component.substring(0, separator))
        val value = decodeSingleRun(component.substring(separator + 1))
        require(key !in result) {
            "Open-Meteo Single Runs query must not contain duplicate keys"
        }
        result[key] = value
    }
    return result
}

private fun formatSingleRunCoordinate(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

@Suppress("DEPRECATION")
private fun encodeSingleRun(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

@Suppress("DEPRECATION")
private fun decodeSingleRun(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
