package com.sl.meteoone.forecast.data.execution

import com.sl.meteoone.forecast.domain.ForecastModelWeightDecision
import com.sl.meteoone.forecast.domain.ForecastModelWeightProvider
import com.sl.meteoone.forecast.domain.ForecastModelWeightRequest
import com.sl.meteoone.forecast.domain.ForecastWeightParameter
import com.sl.meteoone.verification.domain.LeadTimeBucket
import com.sl.meteoone.verification.domain.MeteorologicalSeason
import com.sl.meteoone.verification.domain.VerificationParameter
import com.sl.meteoone.verification.domain.VerificationSample
import com.sl.meteoone.verification.domain.VerificationWeightDecision
import com.sl.meteoone.verification.domain.VerificationWeightPolicy
import com.sl.meteoone.verification.domain.VerificationWeightRequest
import java.time.Duration
import java.time.ZoneId

/**
 * Supplies already-collected M4 verification samples to the synchronous forecast fusion boundary.
 *
 * Acquisition, matching and persistence remain separate M4 capabilities. Callers may precompute or
 * cache samples before invoking forecast execution; this boundary never performs observation I/O.
 */
fun interface VerificationWeightSampleSource {
    fun samples(request: VerificationWeightRequest): Collection<VerificationSample>
}

internal class VerificationForecastModelWeightProvider(
    private val sampleSource: VerificationWeightSampleSource,
    private val policy: VerificationWeightPolicy = VerificationWeightPolicy(),
) : ForecastModelWeightProvider {
    override fun weights(request: ForecastModelWeightRequest): ForecastModelWeightDecision {
        val leadBucket = LeadTimeBucket.from(
            Duration.between(request.modelRun, request.validTime),
        ) ?: return ForecastModelWeightDecision.EqualFallback

        val verificationRequest = VerificationWeightRequest(
            coordinate = request.coordinate,
            season = MeteorologicalSeason.from(
                request.validTime.atZone(ZoneId.of(request.timeZoneId)).month,
            ),
            parameter = request.parameter.toVerificationParameter(),
            leadBucket = leadBucket,
            modelFamilies = request.modelFamilies,
            evaluatedAt = request.evaluatedAt,
        )

        return try {
            when (
                val decision = policy.derive(
                    request = verificationRequest,
                    samples = sampleSource.samples(verificationRequest),
                )
            ) {
                is VerificationWeightDecision.EqualFallback ->
                    ForecastModelWeightDecision.EqualFallback

                is VerificationWeightDecision.Measured ->
                    ForecastModelWeightDecision.Measured(decision.weights)
            }
        } catch (_: Exception) {
            ForecastModelWeightDecision.EqualFallback
        }
    }
}

private fun ForecastWeightParameter.toVerificationParameter(): VerificationParameter = when (this) {
    ForecastWeightParameter.TEMPERATURE -> VerificationParameter.TEMPERATURE
    ForecastWeightParameter.PRESSURE -> VerificationParameter.PRESSURE
    ForecastWeightParameter.WIND -> VerificationParameter.WIND
    ForecastWeightParameter.PRECIPITATION -> VerificationParameter.PRECIPITATION
}
