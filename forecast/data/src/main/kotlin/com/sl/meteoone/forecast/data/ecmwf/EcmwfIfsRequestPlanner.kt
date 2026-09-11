package com.sl.meteoone.forecast.data.ecmwf

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import com.sl.meteoone.forecast.data.source.requireOfficialPlanMetadata
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset

private const val BASE_URL = "https://data.ecmwf.int/forecasts"
private const val MAX_FORECAST_HOUR = 72
private const val MAX_INDEX_RESPONSE_BYTES = 2L * 1024L * 1024L

/**
 * Phase-one plan for ECMWF IFS Open Data.
 *
 * The index is downloaded first. Individual GRIB fields are selected from its
 * JSON-lines metadata and retrieved later with one HTTP byte-range per field.
 */
data class EcmwfIfsRequestPlan(
    val indexRequest: OfficialSourceRequest,
    val gribUri: URI,
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val modelRun: Instant,
    val validTime: Instant,
    val forecastHour: Int,
) {
    init {
        require(provider == ForecastProvider.ECMWF_OPEN_DATA) {
            "ECMWF request plan must use the ECMWF Open Data provider"
        }
        requireOfficialPlanMetadata(
            provider = provider,
            modelFamily = modelFamily,
            modelRun = modelRun,
            validTime = validTime,
            forecastHour = forecastHour,
        )
        val expected = buildRequests(modelRun, forecastHour)
        require(indexRequest == expected.indexRequest) {
            "ECMWF index request URI and transport limits must match the planned run and hour"
        }
        require(gribUri == expected.gribUri) {
            "ECMWF GRIB URI must match the planned run and hour"
        }
    }
}

object EcmwfIfsRequestPlanner {
    fun plan(
        modelRun: Instant,
        forecastHour: Int,
    ): EcmwfIfsRequestPlan {
        val runUtc = modelRun.atOffset(ZoneOffset.UTC)
        require(runUtc.minute == 0 && runUtc.second == 0 && runUtc.nano == 0) {
            "IFS model run must be aligned to an exact UTC hour"
        }
        require(runUtc.hour in setOf(0, 6, 12, 18)) {
            "IFS model run must use a 00, 06, 12, or 18 UTC cycle"
        }
        require(forecastHour in 0..MAX_FORECAST_HOUR) {
            "Forecast hour must be between 0 and $MAX_FORECAST_HOUR"
        }
        require(forecastHour % 3 == 0) {
            "IFS Open Data deterministic output is available every 3 hours"
        }

        val requests = buildRequests(modelRun, forecastHour)
        return EcmwfIfsRequestPlan(
            indexRequest = requests.indexRequest,
            gribUri = requests.gribUri,
            provider = ForecastProvider.ECMWF_OPEN_DATA,
            modelFamily = ModelFamily.ECMWF_IFS,
            modelRun = modelRun,
            validTime = modelRun.plusSeconds(forecastHour * 3600L),
            forecastHour = forecastHour,
        )
    }
}

internal fun ecmwfGribUri(
    modelRun: Instant,
    forecastHour: Int,
): URI = URI.create("${ecmwfProductPrefix(modelRun, forecastHour)}.grib2")

private fun buildRequests(
    modelRun: Instant,
    forecastHour: Int,
): EcmwfRequests {
    val prefix = ecmwfProductPrefix(modelRun, forecastHour)
    return EcmwfRequests(
        indexRequest = OfficialSourceRequest(
            uri = URI.create("$prefix.index"),
            maxResponseBytes = MAX_INDEX_RESPONSE_BYTES,
        ),
        gribUri = ecmwfGribUri(modelRun, forecastHour),
    )
}

private fun ecmwfProductPrefix(
    modelRun: Instant,
    forecastHour: Int,
): String {
    val runUtc = modelRun.atOffset(ZoneOffset.UTC)
    val date = buildString {
        append(runUtc.year.toString().padStart(4, '0'))
        append(runUtc.monthValue.toString().padStart(2, '0'))
        append(runUtc.dayOfMonth.toString().padStart(2, '0'))
    }
    val cycle = runUtc.hour.toString().padStart(2, '0')
    return "$BASE_URL/$date/${cycle}z/ifs/0p25/oper/${date}${cycle}0000-${forecastHour}h-oper-fc"
}

private data class EcmwfRequests(
    val indexRequest: OfficialSourceRequest,
    val gribUri: URI,
)
