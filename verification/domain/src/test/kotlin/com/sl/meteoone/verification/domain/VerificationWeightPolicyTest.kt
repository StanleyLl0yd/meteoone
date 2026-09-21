package com.sl.meteoone.verification.domain

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VerificationWeightPolicyTest {
    private val policy = VerificationWeightPolicy()
    private val coordinate = ForecastCoordinate(59.9, 30.3)
    private val startRun = Instant.parse("2026-09-01T00:00:00Z")
    private val evaluatedAt = Instant.parse("2026-09-16T00:00:00Z")
    private val families = setOf(
        ModelFamily.ECMWF_IFS,
        ModelFamily.DWD_ICON,
        ModelFamily.NOAA_GFS,
    )
    private val station = ObservationStation(
        sourceId = "noaa-ncei-ghcnh",
        stationId = "RSM00026063",
        latitude = 59.9667,
        longitude = 30.3,
        elevationMeters = 4.0,
    )

    @Test
    fun strongStableEvidenceProducesBoundedUnequalLocationWeights() {
        val decision = assertIs<VerificationWeightDecision.Measured>(
            policy.derive(
                request = request(),
                samples = strongCampaign(),
            ),
        )

        assertIs<VerificationAggregationScope.Location>(decision.evidence.scope)
        assertEquals(ModelFamily.ECMWF_IFS, decision.evidence.winningModelFamily)
        assertEquals(140, decision.evidence.independentSampleCounts[ModelFamily.ECMWF_IFS])
        assertEquals(14, decision.evidence.distinctRunCounts[ModelFamily.ECMWF_IFS])
        assertTrue(
            requireNotNull(decision.weights[ModelFamily.ECMWF_IFS]) >
                requireNotNull(decision.weights[ModelFamily.DWD_ICON]),
        )
        assertTrue(
            requireNotNull(decision.weights[ModelFamily.DWD_ICON]) >=
                requireNotNull(decision.weights[ModelFamily.NOAA_GFS]),
        )
        assertEquals(3.0, decision.weights.values.sum(), absoluteTolerance = 1e-12)
        val ratio = decision.weights.values.max() / decision.weights.values.min()
        assertTrue(ratio <= 1.5 + 1e-12)
    }

    @Test
    fun sparseEvidenceFallsBackToEqualWeights() {
        val samples = families.flatMap { family ->
            campaign(
                family = family,
                errorForRun = { scoreFor(family) },
                runCount = 3,
            )
        }

        val decision = assertIs<VerificationWeightDecision.EqualFallback>(
            policy.derive(request(), samples),
        )

        assertEquals(setOf(1.0), decision.weights.values.toSet())
        assertTrue(
            decision.attempts.any {
                it.reason == VerificationWeightFallbackReason.SPARSE_EVIDENCE
            },
        )
    }

    @Test
    fun staleEvidenceFallsBackToEqualWeights() {
        val staleStart = Instant.parse("2026-09-01T00:00:00Z")
        val samples = families.flatMap { family ->
            campaign(
                family = family,
                errorForRun = { scoreFor(family) },
                start = staleStart,
            )
        }

        val decision = assertIs<VerificationWeightDecision.EqualFallback>(
            policy.derive(
                request(evaluatedAt = Instant.parse("2026-11-16T00:00:00Z")),
                samples,
            ),
        )

        assertEquals(setOf(1.0), decision.weights.values.toSet())
        assertTrue(
            decision.attempts.any {
                it.reason == VerificationWeightFallbackReason.STALE_EVIDENCE
            },
        )
    }

    @Test
    fun winnerThatFlipsAcrossChronologicalHalvesIsUnstable() {
        val samples = buildList {
            addAll(
                campaign(ModelFamily.ECMWF_IFS) { run ->
                    if (run < 7) 1.0 else 1.8
                },
            )
            addAll(
                campaign(ModelFamily.DWD_ICON) { run ->
                    if (run < 7) 2.0 else 1.5
                },
            )
            addAll(
                campaign(ModelFamily.NOAA_GFS) { 2.5 },
            )
        }

        val decision = assertIs<VerificationWeightDecision.EqualFallback>(
            policy.derive(request(), samples),
        )

        assertEquals(setOf(1.0), decision.weights.values.toSet())
        assertTrue(
            decision.attempts.any {
                it.reason == VerificationWeightFallbackReason.UNSTABLE_EVIDENCE
            },
        )
    }

    @Test
    fun smallSkillDifferenceIsNotMaterialEnoughToWeight() {
        val samples = buildList {
            addAll(campaign(ModelFamily.ECMWF_IFS) { 1.00 })
            addAll(campaign(ModelFamily.DWD_ICON) { 1.03 })
            addAll(campaign(ModelFamily.NOAA_GFS) { 1.04 })
        }

        val decision = assertIs<VerificationWeightDecision.EqualFallback>(
            policy.derive(request(), samples),
        )

        assertTrue(
            decision.attempts.any {
                it.reason == VerificationWeightFallbackReason.IMMATERIAL_ADVANTAGE
            },
        )
    }

    @Test
    fun sparseLocationCanFallBackToSufficientStableRegionEvidence() {
        val second = ForecastCoordinate(58.0, 31.0)
        val third = ForecastCoordinate(56.0, 34.0)
        val fourth = ForecastCoordinate(57.0, 32.0)
        val samples = buildList {
            families.forEach { family ->
                addAll(
                    campaign(
                        family = family,
                        errorForRun = { scoreFor(family) },
                        coordinate = coordinate,
                        runCount = 2,
                    ),
                )
                addAll(
                    campaign(
                        family = family,
                        errorForRun = { scoreFor(family) },
                        coordinate = second,
                    ),
                )
                addAll(
                    campaign(
                        family = family,
                        errorForRun = { scoreFor(family) },
                        coordinate = third,
                    ),
                )
                addAll(
                    campaign(
                        family = family,
                        errorForRun = { scoreFor(family) },
                        coordinate = fourth,
                    ),
                )
            }
        }

        val decision = assertIs<VerificationWeightDecision.Measured>(
            policy.derive(request(), samples),
        )

        val region = assertIs<VerificationAggregationScope.Region>(
            decision.evidence.scope,
        )
        assertEquals(VerificationRegionKey.from(coordinate), region.key)
        assertEquals(ModelFamily.ECMWF_IFS, decision.evidence.winningModelFamily)
        assertEquals(440, decision.evidence.independentSampleCounts[ModelFamily.ECMWF_IFS])
    }

    @Test
    fun duplicateDeliveryPathDoesNotIncreaseModelFamilyEvidenceOrWeight() {
        val baseline = strongCampaign()
        val duplicateEcmwf = campaign(
            family = ModelFamily.ECMWF_IFS,
            provider = ForecastProvider.OPEN_METEO,
            errorForRun = { 1.0 },
        )
        val decision = assertIs<VerificationWeightDecision.Measured>(
            policy.derive(request(), baseline + duplicateEcmwf),
        )

        assertEquals(
            140,
            decision.evidence.independentSampleCounts[ModelFamily.ECMWF_IFS],
        )
        assertEquals(ModelFamily.ECMWF_IFS, decision.evidence.winningModelFamily)
    }

    @Test
    fun singleModelFamilyAndUnknownFamilyNeverProduceUnequalWeights() {
        val singleRequest = request(
            modelFamilies = setOf(ModelFamily.ECMWF_IFS),
        )
        val single = assertIs<VerificationWeightDecision.EqualFallback>(
            policy.derive(
                singleRequest,
                campaign(ModelFamily.ECMWF_IFS) { 1.0 },
            ),
        )
        assertEquals(mapOf(ModelFamily.ECMWF_IFS to 1.0), single.weights)
        assertEquals(
            VerificationWeightFallbackReason.SINGLE_MODEL_FAMILY,
            single.attempts.single().reason,
        )

        assertFailsWith<IllegalArgumentException> {
            request(
                modelFamilies = setOf(
                    ModelFamily.ECMWF_IFS,
                    ModelFamily.UNKNOWN,
                ),
            )
        }
    }

    @Test
    fun futureEvidenceIsRejectedInsteadOfBeingSilentlyTreatedAsFresh() {
        val futureEvaluation = Instant.parse("2026-09-01T01:00:00Z")
        assertFailsWith<IllegalArgumentException> {
            policy.derive(
                request = request(evaluatedAt = futureEvaluation),
                samples = strongCampaign(),
            )
        }
    }

    private fun strongCampaign(): List<VerificationSample> =
        families.flatMap { family ->
            campaign(family) { scoreFor(family) }
        }

    private fun scoreFor(family: ModelFamily): Double = when (family) {
        ModelFamily.ECMWF_IFS -> 1.0
        ModelFamily.DWD_ICON -> 1.5
        ModelFamily.NOAA_GFS -> 2.0
        ModelFamily.UNKNOWN -> error("Unknown model family is not test evidence")
    }

    private fun request(
        modelFamilies: Set<ModelFamily> = families,
        evaluatedAt: Instant = this.evaluatedAt,
    ): VerificationWeightRequest = VerificationWeightRequest(
        coordinate = coordinate,
        season = MeteorologicalSeason.AUTUMN,
        parameter = VerificationParameter.TEMPERATURE,
        leadBucket = LeadTimeBucket.H6_24,
        modelFamilies = modelFamilies,
        evaluatedAt = evaluatedAt,
    )

    private fun campaign(
        family: ModelFamily,
        provider: ForecastProvider = providerFor(family),
        coordinate: ForecastCoordinate = this.coordinate,
        start: Instant = startRun,
        runCount: Int = 14,
        pointsPerRun: Int = 10,
        errorForRun: (Int) -> Double,
    ): List<VerificationSample> = buildList {
        repeat(runCount) { runIndex ->
            val modelRun = start.plus(Duration.ofDays(runIndex.toLong()))
            repeat(pointsPerRun) { pointIndex ->
                val validTime = modelRun.plus(
                    Duration.ofHours(7L + pointIndex),
                )
                val error = errorForRun(runIndex)
                add(
                    ScalarVerificationSample(
                        context = VerificationContext(
                            coordinate = coordinate,
                            provider = provider,
                            modelFamily = family,
                            modelRun = modelRun,
                            validTime = validTime,
                            timeZoneId = "UTC",
                        ),
                        station = station,
                        parameter = VerificationParameter.TEMPERATURE,
                        observedAt = validTime,
                        predicted = error,
                        observed = 0.0,
                    ),
                )
            }
        }
    }

    private fun providerFor(family: ModelFamily): ForecastProvider = when (family) {
        ModelFamily.ECMWF_IFS -> ForecastProvider.ECMWF_OPEN_DATA
        ModelFamily.DWD_ICON -> ForecastProvider.DWD_OPEN_DATA
        ModelFamily.NOAA_GFS -> ForecastProvider.NOAA_NOMADS
        ModelFamily.UNKNOWN -> error("Unknown model family has no provider")
    }
}
