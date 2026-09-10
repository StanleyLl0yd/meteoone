package com.sl.meteoone.forecast.domain

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast

data class ForecastSourceIdentity(
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
)

sealed interface ForecastSourceResult {
    val identity: ForecastSourceIdentity

    data class Success(
        val forecast: SourceForecast,
    ) : ForecastSourceResult {
        override val identity: ForecastSourceIdentity
            get() = ForecastSourceIdentity(
                provider = forecast.origin.provider,
                modelFamily = forecast.origin.modelFamily,
            )
    }

    data class Failure(
        override val identity: ForecastSourceIdentity,
    ) : ForecastSourceResult
}

sealed interface ForecastOrchestrationResult {
    data class Available(
        val forecast: FusedForecast,
        val successfulSources: List<ForecastSourceIdentity>,
        val failedSources: List<ForecastSourceIdentity>,
    ) : ForecastOrchestrationResult

    data class Unavailable(
        val failedSources: List<ForecastSourceIdentity>,
    ) : ForecastOrchestrationResult
}

class ForecastSourceOrchestrator(
    private val fusionEngine: ForecastFusionEngine = ForecastFusionEngine(),
) {
    fun combine(results: List<ForecastSourceResult>): ForecastOrchestrationResult {
        require(results.isNotEmpty()) { "At least one forecast source result is required" }

        val identities = results.map { it.identity }
        require(identities.size == identities.toSet().size) {
            "Forecast source identities must be unique within one orchestration result set"
        }

        val successes = results.filterIsInstance<ForecastSourceResult.Success>()
        val failedSources = results
            .filterIsInstance<ForecastSourceResult.Failure>()
            .map { it.identity }

        if (successes.isEmpty()) {
            return ForecastOrchestrationResult.Unavailable(
                failedSources = failedSources,
            )
        }

        return ForecastOrchestrationResult.Available(
            forecast = fusionEngine.fuse(successes.map { it.forecast }),
            successfulSources = successes.map { it.identity },
            failedSources = failedSources,
        )
    }
}
