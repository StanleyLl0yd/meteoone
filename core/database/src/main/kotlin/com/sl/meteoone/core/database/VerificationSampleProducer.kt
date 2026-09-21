package com.sl.meteoone.core.database

import com.sl.meteoone.verification.domain.ForecastVerificationPointEvidence
import com.sl.meteoone.verification.domain.ForecastVerificationRunEvidence
import com.sl.meteoone.verification.domain.VerificationSample
import com.sl.meteoone.verification.domain.VerificationSampleMatcher
import java.time.Duration

interface VerificationSampleProducer {
    fun produce(
        forecast: StoredVerificationForecastRun,
        observations: StoredVerificationObservationSeries,
    ): List<VerificationSample>
}

class DefaultVerificationSampleProducer(
    private val matcher: VerificationSampleMatcher = VerificationSampleMatcher(),
) : VerificationSampleProducer {
    override fun produce(
        forecast: StoredVerificationForecastRun,
        observations: StoredVerificationObservationSeries,
    ): List<VerificationSample> = matcher.match(
        forecast = forecast.toEvidence(),
        station = observations.station,
        surfaceObservations = observations.surfaceObservations,
        precipitationObservations = observations.precipitationObservations,
    )
}

private fun StoredVerificationForecastRun.toEvidence(): ForecastVerificationRunEvidence {
    require(!firstCapturedAt.isBefore(modelRun)) {
        "Stored verification forecast capture time must not precede model run"
    }
    return ForecastVerificationRunEvidence(
        coordinate = coordinate,
        provider = provider,
        modelFamily = modelFamily,
        modelRun = modelRun,
        timeZoneId = timeZoneId,
        hourly = hourly.map { point ->
            val derivedLead = Duration.between(modelRun, point.validTime)
            require(point.leadTime == derivedLead) {
                "Stored verification forecast lead does not match model-run provenance"
            }
            ForecastVerificationPointEvidence(
                validTime = point.validTime,
                temperatureC = point.temperatureC,
                pressureSeaLevelHpa = point.pressureSeaLevelHpa,
                windSpeedMps = point.windSpeedMps,
                windDirectionDegrees = point.windDirectionDegrees,
                precipitationMm = point.precipitationMm,
                precipitationInterval = point.precipitationInterval,
            )
        },
    )
}
