package com.sl.meteoone.forecast.data.execution

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import kotlin.ConsistentCopyVisibility

@ConsistentCopyVisibility
data class M1ForecastSourceIdentity internal constructor(
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
)

sealed interface M1ForecastEngineResult {
    @ConsistentCopyVisibility
    data class Available internal constructor(
        val forecast: FusedForecast,
        val sourceForecasts: List<SourceForecast>,
        val successfulSources: List<M1ForecastSourceIdentity>,
        val failedSources: List<M1ForecastSourceIdentity>,
    ) : M1ForecastEngineResult {
        init {
            require(successfulSources.size == successfulSources.toSet().size) {
                "Successful forecast source identities must be unique"
            }
            require(failedSources.size == failedSources.toSet().size) {
                "Failed forecast source identities must be unique"
            }
            require(successfulSources.toSet().intersect(failedSources.toSet()).isEmpty()) {
                "Forecast source identities cannot be both successful and failed"
            }
            val forecastIdentities = sourceForecasts.map { source ->
                M1ForecastSourceIdentity(
                    provider = source.origin.provider,
                    modelFamily = source.origin.modelFamily,
                )
            }
            require(forecastIdentities == successfulSources) {
                "Successful source forecasts must match successful source identities in order"
            }
        }
    }

    @ConsistentCopyVisibility
    data class Unavailable internal constructor(
        val successfulCrossChecks: List<M1ForecastSourceIdentity>,
        val failedSources: List<M1ForecastSourceIdentity>,
    ) : M1ForecastEngineResult {
        init {
            require(successfulCrossChecks.size == successfulCrossChecks.toSet().size) {
                "Successful forecast cross-check identities must be unique"
            }
            require(failedSources.size == failedSources.toSet().size) {
                "Failed forecast source identities must be unique"
            }
            require(successfulCrossChecks.toSet().intersect(failedSources.toSet()).isEmpty()) {
                "Forecast source identities cannot be both successful and failed"
            }
        }
    }
}
