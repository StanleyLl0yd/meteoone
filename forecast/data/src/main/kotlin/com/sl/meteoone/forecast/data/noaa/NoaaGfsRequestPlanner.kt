package com.sl.meteoone.forecast.data.noaa

import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import com.sl.meteoone.forecast.data.source.PlannedForecastRequest
import com.sl.meteoone.forecast.data.source.SourceGridPoint
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.floor

object NoaaGfsRequestPlanner {
    private const val BASE_URL = "https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl"
    private const val GRID_STEP_DEGREES = 0.25
    private const val MAX_FORECAST_HOUR = 72
    private const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L

    private val requestSpacing = Duration.ofSeconds(10)

    private val variables = listOf(
        "var_APCP",
        "var_DPT",
        "var_GUST",
        "var_PRMSL",
        "var_RH",
        "var_TCDC",
        "var_TMP",
        "var_UGRD",
        "var_VGRD",
        "var_VIS",
    )

    private val levels = listOf(
        "lev_2_m_above_ground",
        "lev_10_m_above_ground",
        "lev_entire_atmosphere",
        "lev_mean_sea_level",
        "lev_surface",
    )

    fun plan(
        modelRun: Instant,
        location: ForecastLocation,
        forecastHour: Int,
    ): PlannedForecastRequest {
        val runUtc = modelRun.atOffset(ZoneOffset.UTC)
        require(runUtc.minute == 0 && runUtc.second == 0 && runUtc.nano == 0) {
            "GFS model run must be aligned to an exact UTC hour"
        }
        require(runUtc.hour % 6 == 0) { "GFS model run must use a 00, 06, 12, or 18 UTC cycle" }
        require(forecastHour in 0..MAX_FORECAST_HOUR) {
            "Forecast hour must be between 0 and $MAX_FORECAST_HOUR"
        }

        val gridPoint = SourceGridPoint(
            latitude = snapLatitude(location.latitude),
            longitudeDegreesEast = snapLongitude(location.longitude),
        )
        val cycle = runUtc.hour.toString().padStart(2, '0')
        val runDate = "%04d%02d%02d".format(runUtc.year, runUtc.monthValue, runUtc.dayOfMonth)
        val forecastHourToken = forecastHour.toString().padStart(3, '0')
        val fileName = "gfs.t${cycle}z.pgrb2.0p25.f$forecastHourToken"
        val directory = "/gfs.$runDate/$cycle/atmos"
        val latitude = formatCoordinate(gridPoint.latitude)
        val longitude = formatCoordinate(gridPoint.longitudeDegreesEast)

        val query = buildList {
            add("file" to fileName)
            variables.forEach { add(it to "on") }
            levels.forEach { add(it to "on") }
            add("subregion" to "")
            add("leftlon" to longitude)
            add("rightlon" to longitude)
            add("toplat" to latitude)
            add("bottomlat" to latitude)
            add("dir" to directory)
        }.joinToString("&") { (key, value) -> "$key=${encode(value)}" }

        return PlannedForecastRequest(
            request = OfficialSourceRequest(
                uri = URI.create("$BASE_URL?$query"),
                maxResponseBytes = MAX_RESPONSE_BYTES,
                minimumRequestSpacing = requestSpacing,
            ),
            provider = ForecastProvider.NOAA_NOMADS,
            modelFamily = ModelFamily.NOAA_GFS,
            modelRun = modelRun,
            validTime = modelRun.plusSeconds(forecastHour * 3600L),
            forecastHour = forecastHour,
            gridPoint = gridPoint,
        )
    }

    private fun snapLatitude(latitude: Double): Double =
        snapToGrid(latitude).coerceIn(-90.0, 90.0).normalizeZero()

    private fun snapLongitude(longitude: Double): Double {
        val normalized = ((longitude % 360.0) + 360.0) % 360.0
        val snapped = snapToGrid(normalized)
        return (if (snapped >= 360.0) 0.0 else snapped).normalizeZero()
    }

    private fun snapToGrid(value: Double): Double =
        floor(value / GRID_STEP_DEGREES + 0.5) * GRID_STEP_DEGREES

    private fun Double.normalizeZero(): Double = if (this == -0.0) 0.0 else this

    private fun formatCoordinate(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    @Suppress("DEPRECATION")
    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
