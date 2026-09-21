package com.sl.meteoone.verification.domain

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ModelFamily
import java.time.Duration
import java.time.Instant

data class VerificationWeightPolicyConfig(
    val minimumSamples: Int = 120,
    val minimumDistinctRuns: Int = 14,
    val minimumRegionCoordinates: Int = 3,
    val minimumSplitSamples: Int = 40,
    val minimumSplitRuns: Int = 5,
    val maximumStaleness: Duration = Duration.ofDays(30),
    val materialAdvantageFraction: Double = 0.05,
    val maximumWeightRatio: Double = 1.5,
) {
    init {
        require(minimumSamples > 0)
        require(minimumDistinctRuns >= 2)
        require(minimumRegionCoordinates > 0)
        require(minimumSplitSamples > 0)
        require(minimumSplitRuns > 0)
        require(minimumSplitRuns * 2 <= minimumDistinctRuns) {
            "Split-run gate must fit inside the full-run gate"
        }
        require(!maximumStaleness.isNegative && !maximumStaleness.isZero)
        require(materialAdvantageFraction.isFinite())
        require(materialAdvantageFraction > 0.0 && materialAdvantageFraction < 1.0)
        require(maximumWeightRatio.isFinite() && maximumWeightRatio > 1.0)
    }
}

data class VerificationWeightRequest(
    val coordinate: ForecastCoordinate,
    val season: MeteorologicalSeason,
    val parameter: VerificationParameter,
    val leadBucket: LeadTimeBucket,
    val modelFamilies: Set<ModelFamily>,
    val evaluatedAt: Instant,
) {
    init {
        require(modelFamilies.isNotEmpty()) {
            "Verification weight request requires at least one model family"
        }
        require(ModelFamily.UNKNOWN !in modelFamilies) {
            "Verification weight request cannot include unknown model family"
        }
    }
}

enum class VerificationWeightFallbackReason {
    SINGLE_MODEL_FAMILY,
    MISSING_EVIDENCE,
    SPARSE_EVIDENCE,
    STALE_EVIDENCE,
    INSUFFICIENT_REGION_DIVERSITY,
    IMMATERIAL_ADVANTAGE,
    UNSTABLE_EVIDENCE,
}

data class VerificationWeightAttempt(
    val scope: VerificationAggregationScope,
    val reason: VerificationWeightFallbackReason,
)

data class VerificationWeightEvidenceSummary(
    val scope: VerificationAggregationScope,
    val fullScores: Map<ModelFamily, Double>,
    val firstHalfScores: Map<ModelFamily, Double>,
    val secondHalfScores: Map<ModelFamily, Double>,
    val independentSampleCounts: Map<ModelFamily, Int>,
    val distinctRunCounts: Map<ModelFamily, Int>,
    val latestObservedAt: Map<ModelFamily, Instant>,
    val winningModelFamily: ModelFamily,
    val materialAdvantageFraction: Double,
    val maximumWeightRatio: Double,
)

sealed interface VerificationWeightDecision {
    val weights: Map<ModelFamily, Double>

    data class EqualFallback(
        override val weights: Map<ModelFamily, Double>,
        val attempts: List<VerificationWeightAttempt>,
    ) : VerificationWeightDecision

    data class Measured(
        override val weights: Map<ModelFamily, Double>,
        val evidence: VerificationWeightEvidenceSummary,
    ) : VerificationWeightDecision
}

class VerificationWeightPolicy(
    private val config: VerificationWeightPolicyConfig = VerificationWeightPolicyConfig(),
) {
    fun derive(
        request: VerificationWeightRequest,
        samples: Collection<VerificationSample>,
    ): VerificationWeightDecision {
        val families = request.modelFamilies.sortedBy { it.ordinal }
        if (families.size == 1) {
            return VerificationWeightDecision.EqualFallback(
                weights = equalWeights(families),
                attempts = listOf(
                    VerificationWeightAttempt(
                        scope = VerificationAggregationScope.Location(request.coordinate),
                        reason = VerificationWeightFallbackReason.SINGLE_MODEL_FAMILY,
                    ),
                ),
            )
        }

        val attempts = mutableListOf<VerificationWeightAttempt>()
        val scopes = listOf(
            VerificationAggregationScope.Location(request.coordinate),
            VerificationAggregationScope.Region(
                VerificationRegionKey.from(request.coordinate),
            ),
        )
        scopes.forEach { scope ->
            when (
                val evaluation = evaluateScope(
                    request = request,
                    families = families,
                    scope = scope,
                    samples = samples,
                )
            ) {
                is ScopeEvaluation.Success -> {
                    return VerificationWeightDecision.Measured(
                        weights = deriveWeights(evaluation.fullScores, families),
                        evidence = VerificationWeightEvidenceSummary(
                            scope = scope,
                            fullScores = evaluation.fullScores,
                            firstHalfScores = evaluation.firstHalfScores,
                            secondHalfScores = evaluation.secondHalfScores,
                            independentSampleCounts = evaluation.full.mapValues {
                                it.value.independentSampleCount
                            },
                            distinctRunCounts = evaluation.full.mapValues {
                                it.value.coverage.distinctRunCount
                            },
                            latestObservedAt = evaluation.full.mapValues {
                                it.value.coverage.lastObservedAt
                            },
                            winningModelFamily = evaluation.winner.family,
                            materialAdvantageFraction = evaluation.winner.advantageFraction,
                            maximumWeightRatio = config.maximumWeightRatio,
                        ),
                    )
                }

                is ScopeEvaluation.Failure -> attempts += VerificationWeightAttempt(
                    scope = scope,
                    reason = evaluation.reason,
                )
            }
        }

        return VerificationWeightDecision.EqualFallback(
            weights = equalWeights(families),
            attempts = attempts,
        )
    }

    private fun evaluateScope(
        request: VerificationWeightRequest,
        families: List<ModelFamily>,
        scope: VerificationAggregationScope,
        samples: Collection<VerificationSample>,
    ): ScopeEvaluation {
        val relevant = samples.filter { sample ->
            sample.parameter == request.parameter &&
                sample.context.season == request.season &&
                sample.context.leadBucket == request.leadBucket &&
                sample.context.modelFamily in request.modelFamilies &&
                sample.belongsTo(scope)
        }
        if (relevant.isEmpty()) {
            return ScopeEvaluation.Failure(
                VerificationWeightFallbackReason.MISSING_EVIDENCE,
            )
        }

        val full = aggregatesByFamily(relevant, request, scope)
        if (!full.keys.containsAll(families)) {
            return ScopeEvaluation.Failure(
                VerificationWeightFallbackReason.MISSING_EVIDENCE,
            )
        }
        if (
            full.values.any {
                it.independentSampleCount < config.minimumSamples ||
                    it.coverage.distinctRunCount < config.minimumDistinctRuns
            }
        ) {
            return ScopeEvaluation.Failure(
                VerificationWeightFallbackReason.SPARSE_EVIDENCE,
            )
        }
        if (
            scope is VerificationAggregationScope.Region &&
            full.values.any {
                it.coverage.distinctCoordinateCount < config.minimumRegionCoordinates
            }
        ) {
            return ScopeEvaluation.Failure(
                VerificationWeightFallbackReason.INSUFFICIENT_REGION_DIVERSITY,
            )
        }
        full.values.forEach { aggregate ->
            require(!aggregate.coverage.lastObservedAt.isAfter(request.evaluatedAt)) {
                "Verification evidence cannot be newer than weight evaluation time"
            }
        }
        if (
            full.values.any {
                Duration.between(
                    it.coverage.lastObservedAt,
                    request.evaluatedAt,
                ) > config.maximumStaleness
            }
        ) {
            return ScopeEvaluation.Failure(
                VerificationWeightFallbackReason.STALE_EVIDENCE,
            )
        }

        val runTimes = relevant
            .map { it.context.modelRun }
            .distinct()
            .sorted()
        if (runTimes.size < config.minimumDistinctRuns) {
            return ScopeEvaluation.Failure(
                VerificationWeightFallbackReason.SPARSE_EVIDENCE,
            )
        }
        val splitIndex = runTimes.size / 2
        if (splitIndex == 0 || splitIndex == runTimes.size) {
            return ScopeEvaluation.Failure(
                VerificationWeightFallbackReason.SPARSE_EVIDENCE,
            )
        }
        val firstRuns = runTimes.take(splitIndex).toSet()
        val secondRuns = runTimes.drop(splitIndex).toSet()
        val first = aggregatesByFamily(
            relevant.filter { it.context.modelRun in firstRuns },
            request,
            scope,
        )
        val second = aggregatesByFamily(
            relevant.filter { it.context.modelRun in secondRuns },
            request,
            scope,
        )
        if (!first.keys.containsAll(families) || !second.keys.containsAll(families)) {
            return ScopeEvaluation.Failure(
                VerificationWeightFallbackReason.SPARSE_EVIDENCE,
            )
        }
        if (
            (first.values + second.values).any {
                it.independentSampleCount < config.minimumSplitSamples ||
                    it.coverage.distinctRunCount < config.minimumSplitRuns
            }
        ) {
            return ScopeEvaluation.Failure(
                VerificationWeightFallbackReason.SPARSE_EVIDENCE,
            )
        }
        if (
            scope is VerificationAggregationScope.Region &&
            (first.values + second.values).any {
                it.coverage.distinctCoordinateCount < config.minimumRegionCoordinates
            }
        ) {
            return ScopeEvaluation.Failure(
                VerificationWeightFallbackReason.INSUFFICIENT_REGION_DIVERSITY,
            )
        }

        val fullScores = scoreMap(full, request.parameter, families)
        val firstScores = scoreMap(first, request.parameter, families)
        val secondScores = scoreMap(second, request.parameter, families)

        val winner = materialWinner(fullScores)
            ?: return ScopeEvaluation.Failure(
                VerificationWeightFallbackReason.IMMATERIAL_ADVANTAGE,
            )
        val firstWinner = materialWinner(firstScores)
        val secondWinner = materialWinner(secondScores)
        if (
            firstWinner == null ||
            secondWinner == null ||
            firstWinner.family != winner.family ||
            secondWinner.family != winner.family
        ) {
            return ScopeEvaluation.Failure(
                VerificationWeightFallbackReason.UNSTABLE_EVIDENCE,
            )
        }

        return ScopeEvaluation.Success(
            full = full,
            fullScores = fullScores,
            firstHalfScores = firstScores,
            secondHalfScores = secondScores,
            winner = winner,
        )
    }

    private fun aggregatesByFamily(
        samples: Collection<VerificationSample>,
        request: VerificationWeightRequest,
        scope: VerificationAggregationScope,
    ): Map<ModelFamily, VerificationSkillAggregate> =
        VerificationSkillAggregator
            .aggregate(samples)
            .filter {
                it.key.scope == scope &&
                    it.key.season == request.season &&
                    it.key.parameter == request.parameter &&
                    it.key.leadBucket == request.leadBucket &&
                    it.key.modelFamily in request.modelFamilies
            }
            .associateBy { it.key.modelFamily }

    private fun scoreMap(
        aggregates: Map<ModelFamily, VerificationSkillAggregate>,
        parameter: VerificationParameter,
        families: List<ModelFamily>,
    ): Map<ModelFamily, Double> {
        val result = linkedMapOf<ModelFamily, Double>()
        families.forEach { family ->
            val aggregate = requireNotNull(aggregates[family])
            val score = when (parameter) {
                VerificationParameter.WIND -> requireNotNull(aggregate.wind)
                    .meanVectorErrorMps

                VerificationParameter.TEMPERATURE,
                VerificationParameter.PRESSURE,
                VerificationParameter.PRECIPITATION,
                -> requireNotNull(aggregate.scalar).mae
            }
            require(score.isFinite() && score >= 0.0)
            result[family] = score
        }
        return result
    }

    private fun materialWinner(
        scores: Map<ModelFamily, Double>,
    ): MaterialWinner? {
        if (scores.size < 2) return null
        val ranked = scores.entries.sortedWith(
            compareBy<Map.Entry<ModelFamily, Double>>(
                { it.value },
                { it.key.ordinal },
            ),
        )
        val best = ranked[0]
        val second = ranked[1]
        val advantage = if (second.value == 0.0) {
            0.0
        } else {
            (second.value - best.value) / second.value
        }
        if (
            !advantage.isFinite() ||
            advantage < config.materialAdvantageFraction
        ) {
            return null
        }
        return MaterialWinner(
            family = best.key,
            advantageFraction = advantage,
        )
    }

    private fun deriveWeights(
        scores: Map<ModelFamily, Double>,
        families: List<ModelFamily>,
    ): Map<ModelFamily, Double> {
        val best = families.minOf { requireNotNull(scores[it]) }
        val minimumRelativeWeight = 1.0 / config.maximumWeightRatio
        val relative = linkedMapOf<ModelFamily, Double>()
        families.forEach { family ->
            val score = requireNotNull(scores[family])
            relative[family] = when {
                best == 0.0 && score == 0.0 -> 1.0
                best == 0.0 -> minimumRelativeWeight
                else -> (best / score).coerceAtLeast(minimumRelativeWeight)
            }
        }
        val normalization = families.size / relative.values.sum()
        val result = linkedMapOf<ModelFamily, Double>()
        families.forEach { family ->
            result[family] = requireNotNull(relative[family]) * normalization
        }
        val min = result.values.min()
        val max = result.values.max()
        check(max / min <= config.maximumWeightRatio + 1e-12) {
            "Derived verification weights exceed the configured safety ratio"
        }
        return result
    }

    private fun equalWeights(
        families: List<ModelFamily>,
    ): Map<ModelFamily, Double> {
        val result = linkedMapOf<ModelFamily, Double>()
        families.forEach { family -> result[family] = 1.0 }
        return result
    }
}

private data class MaterialWinner(
    val family: ModelFamily,
    val advantageFraction: Double,
)

private sealed interface ScopeEvaluation {
    data class Success(
        val full: Map<ModelFamily, VerificationSkillAggregate>,
        val fullScores: Map<ModelFamily, Double>,
        val firstHalfScores: Map<ModelFamily, Double>,
        val secondHalfScores: Map<ModelFamily, Double>,
        val winner: MaterialWinner,
    ) : ScopeEvaluation

    data class Failure(
        val reason: VerificationWeightFallbackReason,
    ) : ScopeEvaluation
}

private fun VerificationSample.belongsTo(
    scope: VerificationAggregationScope,
): Boolean = when (scope) {
    is VerificationAggregationScope.Location ->
        context.coordinate == scope.coordinate

    is VerificationAggregationScope.Region ->
        VerificationRegionKey.from(context.coordinate) == scope.key
}
