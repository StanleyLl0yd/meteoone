package com.sl.meteoone.forecast.data.execution

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.domain.ForecastModelWeightDecision
import com.sl.meteoone.forecast.domain.ForecastModelWeightRequest
import com.sl.meteoone.forecast.domain.ForecastWeightParameter
import com.sl.meteoone.verification.domain.MeteorologicalSeason
import com.sl.meteoone.verification.domain.ObservationStation
import com.sl.meteoone.verification.domain.ScalarVerificationSample
import com.sl.meteoone.verification.domain.VerificationContext
import com.sl.meteoone.verification.domain.VerificationParameter
import com.sl.meteoone.verification.domain.VerificationSample
import com.sl.meteoone.verification.domain.VerificationWeightRequest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VerificationForecastModelWeightProviderTest {
    private val coordinate = ForecastCoordinate(59.9, 30.3)
    private val evaluatedAt = Instant.parse("2026-09-20T00:00:00Z")
    private val currentRun = Instant.parse("2026-09-19T00:00:00Z")
    private val families = setOf(
        ModelFamily.ECMWF_IFS,
        ModelFamily.DWD_ICON,
        ModelFamily.NOAA_GFS,
    )
    private val station = ObservationStation(
        sourceId = "NOAA_GHCNH",
        stationId = "TEST0000001",
        latitude = 59.8,
        longitude = 30.2,
        elevationMeters = 12.0,
    )

    @Test
    fun stableM4EvidenceBecomesMeasuredFusionWeights() {
        var captured: VerificationWeightRequest? = null
        val provider = VerificationForecastModelWeightProvider(
            sampleSource = VerificationWeightSampleSource { request ->
                captured = request
                stableCampaign()
            },
        )

        val decision = assertIs<ForecastModelWeightDecision.Measured>(
            provider.weights(request()),
        )

        assertEquals(
            VerificationParameter.TEMPERATURE,
            requireNotNull(captured).parameter,
        )
        assertEquals(MeteorologicalSeason.AUTUMN, requireNotNull(captured).season)
        assertEquals(families, decision.weights.keys)
        assertTrue(
            decision.weights.getValue(ModelFamily.ECMWF_IFS) >
                decision.weights.getValue(ModelFamily.DWD_ICON),
        )
        assertTrue(
            decision.weights.getValue(ModelFamily.DWD_ICON) >
                decision.weights.getValue(ModelFamily.NOAA_GFS),
        )
        assertTrue(
            decision.weights.values.max() / decision.weights.values.min() <= 1.5 + 1e-12,
        )
    }

    @Test
    fun missingEvidenceKeepsEqualFusionFallback() {
        val provider = VerificationForecastModelWeightProvider(
            sampleSource = VerificationWeightSampleSource { emptyList() },
        )

        assertIs<ForecastModelWeightDecision.EqualFallback>(
            provider.weights(request()),
        )
    }

    @Test
    fun leadOutsideVerified72HoursDoesNotQueryEvidence() {
        var queries = 0
        val provider = VerificationForecastModelWeightProvider(
            sampleSource = VerificationWeightSampleSource {
                queries += 1
                stableCampaign()
            },
        )

        val decision = provider.weights(
            request(validTime = currentRun.plus(Duration.ofHours(73))),
        )

        assertIs<ForecastModelWeightDecision.EqualFallback>(decision)
        assertEquals(0, queries)
    }

    private fun request(
        validTime: Instant = currentRun.plus(Duration.ofHours(12)),
    ) = ForecastModelWeightRequest(
        coordinate = coordinate,
        modelRun = currentRun,
        validTime = validTime,
        timeZoneId = "Europe/Moscow",
        parameter = ForecastWeightParameter.TEMPERATURE,
        modelFamilies = families,
        evaluatedAt = evaluatedAt,
    )

    private fun stableCampaign(): List<VerificationSample> = families.flatMap { family ->
        buildList {
            repeat(14) { runIndex ->
                val modelRun = Instant.parse("2026-09-01T00:00:00Z")
                    .plus(Duration.ofDays(runIndex.toLong()))
                repeat(10) { pointIndex ->
                    val validTime = modelRun.plus(Duration.ofHours(7L + pointIndex))
                    add(
                        ScalarVerificationSample(
                            context = VerificationContext(
                                coordinate = coordinate,
                                provider = providerFor(family),
                                modelFamily = family,
                                modelRun = modelRun,
                                validTime = validTime,
                                timeZoneId = "Europe/Moscow",
                            ),
                            station = station,
                            parameter = VerificationParameter.TEMPERATURE,
                            observedAt = validTime,
                            predicted = scoreFor(family),
                            observed = 0.0,
                        ),
                    )
                }
            }
        }
    }

    private fun scoreFor(family: ModelFamily): Double = when (family) {
        ModelFamily.ECMWF_IFS -> 1.0
        ModelFamily.DWD_ICON -> 1.5
        ModelFamily.NOAA_GFS -> 2.0
        ModelFamily.UNKNOWN -> error("Unknown model family is not verification evidence")
    }

    private fun providerFor(family: ModelFamily): ForecastProvider = when (family) {
        ModelFamily.ECMWF_IFS -> ForecastProvider.ECMWF_OPEN_DATA
        ModelFamily.DWD_ICON -> ForecastProvider.DWD_OPEN_DATA
        ModelFamily.NOAA_GFS -> ForecastProvider.NOAA_NOMADS
        ModelFamily.UNKNOWN -> error("Unknown model family has no verification provider")
    }
}
