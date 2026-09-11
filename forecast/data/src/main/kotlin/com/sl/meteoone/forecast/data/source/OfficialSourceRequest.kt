package com.sl.meteoone.forecast.data.source

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.net.URI
import java.time.Duration
import java.time.Instant

private const val MAX_SOURCE_RESPONSE_BYTES = 64L * 1024L * 1024L

data class OfficialSourceRequest(
    val uri: URI,
    val maxResponseBytes: Long,
    val minimumRequestSpacing: Duration = Duration.ZERO,
) {
    init {
        require(uri.scheme.equals("https", ignoreCase = true)) { "Official source requests must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Official source request must have a host" }
        require(maxResponseBytes in 1..MAX_SOURCE_RESPONSE_BYTES) { "Response byte limit is out of bounds" }
        require(!minimumRequestSpacing.isNegative) { "Request spacing must not be negative" }
    }
}

data class SourceGridPoint(
    val latitude: Double,
    val longitudeDegreesEast: Double,
) {
    init {
        require(latitude in -90.0..90.0)
        require(longitudeDegreesEast >= 0.0 && longitudeDegreesEast < 360.0)
    }
}

data class PlannedForecastRequest(
    val request: OfficialSourceRequest,
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val modelRun: Instant,
    val validTime: Instant,
    val forecastHour: Int,
    val gridPoint: SourceGridPoint,
) {
    init {
        requireOfficialPlanMetadata(
            provider = provider,
            modelFamily = modelFamily,
            modelRun = modelRun,
            validTime = validTime,
            forecastHour = forecastHour,
        )
    }
}

internal fun requireOfficialPlanMetadata(
    provider: ForecastProvider,
    modelFamily: ModelFamily,
    modelRun: Instant,
    validTime: Instant,
    forecastHour: Int,
) {
    val expectedModelFamily = requireNotNull(OfficialProviderIdentity.modelFamily(provider)) {
        "Provider $provider is not a direct official model source"
    }
    require(modelFamily == expectedModelFamily) {
        "Provider $provider must use model family $expectedModelFamily"
    }
    require(forecastHour >= 0) { "Forecast hour must not be negative" }
    require(Duration.between(modelRun, validTime) == Duration.ofHours(forecastHour.toLong())) {
        "Forecast valid time must equal model run plus forecast hour"
    }
}
