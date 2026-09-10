package com.sl.meteoone.forecast.data.ecmwf

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.data.source.OfficialProviderIdentity
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import java.net.URI
import java.time.Duration
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
        val expectedModelFamily = requireNotNull(OfficialProviderIdentity.modelFamily(provider)) {
            "ECMWF request plan provider must be a direct official model source"
        }
        require(modelFamily == expectedModelFamily) {
            "ECMWF request plan must use model family $expectedModelFamily"
        }
        require(forecastHour >= 0) { "Forecast hour must not be negative" }
        require(Duration.between(modelRun, validTime) == Duration.ofHours(forecastHour.toLong())) {
            "ECMWF forecast valid time must equal model run plus forecast hour"
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

        val date = buildString {
            append(runUtc.year.toString().padStart(4, '0'))
            append(runUtc.monthValue.toString().padStart(2, '0'))
            append(runUtc.dayOfMonth.toString().padStart(2, '0'))
        }
        val cycle = runUtc.hour.toString().padStart(2, '0')
        val prefix = "$BASE_URL/$date/${cycle}z/ifs/0p25/oper/${date}${cycle}0000-${forecastHour}h-oper-fc"
        val gribUri = URI.create("$prefix.grib2")

        return EcmwfIfsRequestPlan(
            indexRequest = OfficialSourceRequest(
                uri = URI.create("$prefix.index"),
                maxResponseBytes = MAX_INDEX_RESPONSE_BYTES,
            ),
            gribUri = gribUri,
            provider = ForecastProvider.ECMWF_OPEN_DATA,
            modelFamily = ModelFamily.ECMWF_IFS,
            modelRun = modelRun,
            validTime = modelRun.plusSeconds(forecastHour * 3600L),
            forecastHour = forecastHour,
        )
    }
}
