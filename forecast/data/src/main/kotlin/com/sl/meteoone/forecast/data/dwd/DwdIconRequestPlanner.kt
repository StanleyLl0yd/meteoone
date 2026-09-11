package com.sl.meteoone.forecast.data.dwd

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import com.sl.meteoone.forecast.data.source.requireOfficialPlanMetadata
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset

private const val BASE_URL = "https://opendata.dwd.de/weather/nwp/icon/grib"
private const val MAX_FORECAST_HOUR = 72
private const val MAX_COMPRESSED_FIELD_BYTES = 8L * 1024L * 1024L

enum class DwdIconField(
    val directory: String,
    val fileToken: String,
    val firstForecastHour: Int = 0,
) {
    TEMPERATURE_2M("t_2m", "T_2M"),
    DEW_POINT_2M("td_2m", "TD_2M"),
    RELATIVE_HUMIDITY_2M("relhum_2m", "RELHUM_2M"),
    PRESSURE_MEAN_SEA_LEVEL("pmsl", "PMSL"),
    WIND_U_10M("u_10m", "U_10M"),
    WIND_V_10M("v_10m", "V_10M"),
    WIND_MAX_10M("vmax_10m", "VMAX_10M", firstForecastHour = 1),
    TOTAL_PRECIPITATION("tot_prec", "TOT_PREC"),
    TOTAL_CLOUD_COVER("clct", "CLCT"),
    WEATHER_CODE("ww", "WW"),
}

data class DwdIconRequestPlan(
    val request: OfficialSourceRequest,
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val modelRun: Instant,
    val validTime: Instant,
    val forecastHour: Int,
    val field: DwdIconField,
) {
    init {
        require(provider == ForecastProvider.DWD_OPEN_DATA) {
            "DWD request plan must use the DWD Open Data provider"
        }
        requireOfficialPlanMetadata(
            provider = provider,
            modelFamily = modelFamily,
            modelRun = modelRun,
            validTime = validTime,
            forecastHour = forecastHour,
        )
        require(forecastHour >= field.firstForecastHour) {
            "${field.name} is not available at forecast hour $forecastHour"
        }
        require(request == buildRequest(modelRun, forecastHour, field)) {
            "DWD request URI and transport limits must match the planned run, hour, and field"
        }
    }
}

object DwdIconRequestPlanner {
    fun plan(
        modelRun: Instant,
        forecastHour: Int,
        field: DwdIconField,
    ): DwdIconRequestPlan {
        val runUtc = modelRun.atOffset(ZoneOffset.UTC)
        require(runUtc.minute == 0 && runUtc.second == 0 && runUtc.nano == 0) {
            "ICON model run must be aligned to an exact UTC hour"
        }
        require(runUtc.hour in setOf(0, 6, 12, 18)) {
            "ICON model run must use a 00, 06, 12, or 18 UTC cycle"
        }
        require(forecastHour in 0..MAX_FORECAST_HOUR) {
            "Forecast hour must be between 0 and $MAX_FORECAST_HOUR"
        }
        require(forecastHour >= field.firstForecastHour) {
            "${field.name} is not available at forecast hour $forecastHour"
        }

        return DwdIconRequestPlan(
            request = buildRequest(modelRun, forecastHour, field),
            provider = ForecastProvider.DWD_OPEN_DATA,
            modelFamily = ModelFamily.DWD_ICON,
            modelRun = modelRun,
            validTime = modelRun.plusSeconds(forecastHour * 3600L),
            forecastHour = forecastHour,
            field = field,
        )
    }
}

private fun buildRequest(
    modelRun: Instant,
    forecastHour: Int,
    field: DwdIconField,
): OfficialSourceRequest {
    val runUtc = modelRun.atOffset(ZoneOffset.UTC)
    val date = buildString {
        append(runUtc.year.toString().padStart(4, '0'))
        append(runUtc.monthValue.toString().padStart(2, '0'))
        append(runUtc.dayOfMonth.toString().padStart(2, '0'))
    }
    val cycle = runUtc.hour.toString().padStart(2, '0')
    val forecastHourToken = forecastHour.toString().padStart(3, '0')
    val filename =
        "icon_global_icosahedral_single-level_${date}${cycle}_" +
            "${forecastHourToken}_${field.fileToken}.grib2.bz2"

    return OfficialSourceRequest(
        uri = URI.create("$BASE_URL/$cycle/${field.directory}/$filename"),
        maxResponseBytes = MAX_COMPRESSED_FIELD_BYTES,
    )
}
