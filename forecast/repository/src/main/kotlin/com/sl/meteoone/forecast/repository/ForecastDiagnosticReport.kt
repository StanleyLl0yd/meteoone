package com.sl.meteoone.forecast.repository

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.domain.ForecastDiagnosticFallbackReason
import com.sl.meteoone.forecast.domain.ForecastDiagnosticProbe
import com.sl.meteoone.forecast.domain.ForecastWeightParameter
import java.time.Instant

private const val MAX_DIAGNOSTIC_COUNT = 10_000

data class ForecastDiagnosticSource(
    val provider: ForecastProvider,
    val family: ModelFamily,
    val succeeded: Boolean,
    val nativeDecodeSuccesses: Int,
) {
    val officialGribSelected: Boolean
        get() = succeeded && nativeDecodeSuccesses > 0 &&
            provider in setOf(
                ForecastProvider.NOAA_NOMADS,
                ForecastProvider.ECMWF_OPEN_DATA,
                ForecastProvider.DWD_OPEN_DATA,
            )
}

data class ForecastDiagnosticWeight(
    val parameter: ForecastWeightParameter,
    val measured: Boolean,
    val fallback: ForecastDiagnosticFallbackReason?,
    val count: Int,
)

data class ForecastDiagnosticReport(
    val capturedAt: Instant,
    val refreshOutcome: ForecastRefreshResult,
    val sources: List<ForecastDiagnosticSource>,
    val exactRunAcquisitionAttempted: Boolean,
    val storedRuns: Int,
    val ghcnhObservations: Int,
    val samples: Map<ForecastWeightParameter, Int>,
    val weights: List<ForecastDiagnosticWeight>,
    val m4Failed: Boolean,
) {
    /** Strict field allowlist: no coordinates, station IDs, paths, URLs or network exceptions. */
    fun sanitizedText(version: String, abi: String): String = buildString {
        appendLine("MeteoOne device diagnostics")
        appendLine("Version: $version")
        appendLine("ABI: $abi")
        appendLine("UTC: $capturedAt")
        appendLine("Refresh: ${refreshOutcome.javaClass.simpleName}")
        sources.forEach {
            appendLine(
                "Source: ${it.provider.name}/${it.family.name} " +
                    "success=${it.succeeded} officialGrib=${it.officialGribSelected} " +
                    "nativeDecodeSuccesses=${it.nativeDecodeSuccesses}",
            )
        }
        appendLine("M4 exact run attempted: $exactRunAcquisitionAttempted")
        appendLine("M4 stored runs: $storedRuns")
        appendLine("M4 GHCNh observations: $ghcnhObservations")
        ForecastWeightParameter.entries.forEach { parameter ->
            appendLine("M4 ${parameter.name} samples: ${samples[parameter] ?: 0}")
        }
        weights.forEach {
            appendLine(
                "Weight ${it.parameter.name}: ${if (it.measured) "measured" else "equal_fallback"} " +
                    "reason=${it.fallback?.name ?: "NONE"} count=${it.count}",
            )
        }
        appendLine("M4 preparation failed: $m4Failed")
    }
}

/** All state exists only during the user's one explicit diagnostic refresh. */
internal class RefreshDiagnosticRecorder : ForecastDiagnosticProbe {
    private var active = false
    private val sourceResults = linkedMapOf<Pair<ForecastProvider, ModelFamily>, Boolean>()
    private val decoded = mutableMapOf<ForecastProvider, Int>()
    private val weightCounts =
        linkedMapOf<Triple<ForecastWeightParameter, Boolean, ForecastDiagnosticFallbackReason?>, Int>()
    private var attemptedRuns = false
    private var runCount = 0
    private var observationCount = 0
    private var sampleCounts = emptyMap<ForecastWeightParameter, Int>()
    private var preparationFailed = false

    @Synchronized
    fun start() {
        clear()
        active = true
    }

    @Synchronized
    fun discard() {
        active = false
        clear()
    }

    @Synchronized
    fun finish(outcome: ForecastRefreshResult, at: Instant): ForecastDiagnosticReport {
        check(active)
        active = false
        val result = ForecastDiagnosticReport(
            capturedAt = at,
            refreshOutcome = outcome,
            sources = sourceResults.map { (identity, success) ->
                ForecastDiagnosticSource(
                    provider = identity.first,
                    family = identity.second,
                    succeeded = success,
                    nativeDecodeSuccesses = decoded[identity.first] ?: 0,
                )
            },
            exactRunAcquisitionAttempted = attemptedRuns,
            storedRuns = runCount,
            ghcnhObservations = observationCount,
            samples = sampleCounts.toMap(),
            weights = weightCounts.map { (identity, count) ->
                ForecastDiagnosticWeight(
                    parameter = identity.first,
                    measured = identity.second,
                    fallback = identity.third,
                    count = count,
                )
            },
            m4Failed = preparationFailed,
        )
        clear()
        return result
    }

    @Synchronized
    override fun sourceResult(provider: ForecastProvider, family: ModelFamily, succeeded: Boolean) {
        if (active && sourceResults.size < 8) sourceResults[provider to family] = succeeded
    }

    @Synchronized
    override fun nativeDecodeSucceeded(provider: ForecastProvider) {
        if (active) decoded[provider] = increment(decoded[provider] ?: 0)
    }

    @Synchronized
    override fun weightDecision(
        parameter: ForecastWeightParameter,
        measured: Boolean,
        fallback: ForecastDiagnosticFallbackReason?,
    ) {
        if (!active) return
        val key = Triple(parameter, measured, fallback)
        weightCounts[key] = increment(weightCounts[key] ?: 0)
    }

    @Synchronized
    override fun m4RunAcquisition(attempted: Boolean) {
        if (active) attemptedRuns = attempted
    }

    @Synchronized
    override fun m4StoredRuns(count: Int) {
        if (active) runCount = count.coerceIn(0, MAX_DIAGNOSTIC_COUNT)
    }

    @Synchronized
    override fun m4Evidence(observationCount: Int, samples: Map<ForecastWeightParameter, Int>) {
        if (!active) return
        this.observationCount = observationCount.coerceIn(0, MAX_DIAGNOSTIC_COUNT)
        sampleCounts = samples.mapValues { (_, count) -> count.coerceIn(0, MAX_DIAGNOSTIC_COUNT) }
    }

    @Synchronized
    override fun m4Failure() {
        if (active) preparationFailed = true
    }

    private fun increment(current: Int): Int = (current + 1).coerceAtMost(MAX_DIAGNOSTIC_COUNT)

    private fun clear() {
        sourceResults.clear()
        decoded.clear()
        weightCounts.clear()
        attemptedRuns = false
        runCount = 0
        observationCount = 0
        sampleCounts = emptyMap()
        preparationFailed = false
    }
}
