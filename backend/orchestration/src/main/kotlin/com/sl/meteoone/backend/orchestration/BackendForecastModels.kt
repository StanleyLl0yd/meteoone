package com.sl.meteoone.backend.orchestration

import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.forecast.domain.ForecastSourceIdentity

enum class BackendForecastFailureReason {
    MISSING_CREDENTIAL,
    CIRCUIT_OPEN,
    CANCELLED,
    IO,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
}

data class BackendFailedSource(
    val identity: ForecastSourceIdentity,
    val reason: BackendForecastFailureReason,
)

sealed interface BackendForecastResult {
    data class Available(
        val forecast: FusedForecast,
        val sourceForecasts: List<SourceForecast>,
        val failedSources: List<BackendFailedSource>,
    ) : BackendForecastResult {
        init {
            require(sourceForecasts.all { source -> source.location == forecast.location }) {
                "Backend source forecast locations must match the fused forecast"
            }
            val successful = sourceForecasts.map { source ->
                ForecastSourceIdentity(
                    provider = source.origin.provider,
                    modelFamily = source.origin.modelFamily,
                )
            }
            require(successful.size == successful.toSet().size) {
                "Backend successful source identities must be unique"
            }
            val failed = failedSources.map(BackendFailedSource::identity)
            require(failed.size == failed.toSet().size) {
                "Backend failed source identities must be unique"
            }
            require(successful.toSet().intersect(failed.toSet()).isEmpty()) {
                "Backend source identity cannot be both successful and failed"
            }
        }
    }

    data class Unavailable(
        val successfulCrossChecks: List<ForecastSourceIdentity>,
        val failedSources: List<BackendFailedSource>,
    ) : BackendForecastResult {
        init {
            require(successfulCrossChecks.size == successfulCrossChecks.toSet().size) {
                "Backend successful cross-check identities must be unique"
            }
            val failed = failedSources.map(BackendFailedSource::identity)
            require(failed.size == failed.toSet().size) {
                "Backend failed source identities must be unique"
            }
            require(successfulCrossChecks.toSet().intersect(failed.toSet()).isEmpty()) {
                "Backend source identity cannot be both successful and failed"
            }
        }
    }
}
