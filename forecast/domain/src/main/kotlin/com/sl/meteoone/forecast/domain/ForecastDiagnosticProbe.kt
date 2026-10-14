package com.sl.meteoone.forecast.domain

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily

/** Opt-in, aggregate-only evidence. Implementations must never receive location or raw payload data. */
interface ForecastDiagnosticProbe {
    val collecting: Boolean get() = false
    fun sourceResult(provider: ForecastProvider, family: ModelFamily, succeeded: Boolean) {}
    fun nativeDecodeSucceeded(provider: ForecastProvider) {}
    fun weightDecision(
        parameter: ForecastWeightParameter,
        measured: Boolean,
        fallback: ForecastDiagnosticFallbackReason?,
    ) {}
    fun m4RunAcquisition(attempted: Boolean) {}
    fun m4StoredRuns(count: Int) {}
    fun m4Evidence(observationCount: Int, samples: Map<ForecastWeightParameter, Int>) {}
    fun m4Failure() {}
}

object NoOpForecastDiagnosticProbe : ForecastDiagnosticProbe

enum class ForecastDiagnosticFallbackReason {
    INVALID_LEAD,
    SINGLE_MODEL_FAMILY,
    MISSING_EVIDENCE,
    SPARSE_EVIDENCE,
    STALE_EVIDENCE,
    INSUFFICIENT_REGION_DIVERSITY,
    IMMATERIAL_ADVANTAGE,
    UNSTABLE_EVIDENCE,
    EVALUATION_ERROR,
}
