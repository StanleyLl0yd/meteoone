package com.sl.meteoone.verification.domain

import com.sl.meteoone.core.model.ForecastInterval
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object VerificationMetrics {
    fun scalarError(
        predicted: Double,
        observed: Double,
    ): ScalarError {
        require(predicted.isFinite() && observed.isFinite()) {
            "Scalar verification values must be finite"
        }
        val signed = predicted - observed
        return ScalarError(
            signed = signed,
            absolute = abs(signed),
            squared = signed * signed,
        )
    }

    fun windVectorError(
        predictedSpeedMps: Double,
        predictedDirectionDegrees: Double?,
        observedSpeedMps: Double,
        observedDirectionDegrees: Double?,
    ): WindVectorError? {
        val predicted = windComponents(
            speedMps = predictedSpeedMps,
            directionDegrees = predictedDirectionDegrees,
        ) ?: return null
        val observed = windComponents(
            speedMps = observedSpeedMps,
            directionDegrees = observedDirectionDegrees,
        ) ?: return null

        val uError = predicted.first - observed.first
        val vError = predicted.second - observed.second
        return WindVectorError(
            uErrorMps = uError,
            vErrorMps = vError,
            magnitudeMps = hypot(uError, vError),
        )
    }

    fun precipitationError(
        predictedAmountMm: Double,
        predictedInterval: ForecastInterval,
        observed: PrecipitationObservation,
    ): ScalarError? {
        require(predictedAmountMm.isFinite() && predictedAmountMm >= 0.0) {
            "Predicted precipitation must be finite and non-negative"
        }
        if (predictedInterval != observed.interval) {
            return null
        }
        return scalarError(
            predicted = predictedAmountMm,
            observed = observed.amountMm,
        )
    }

    private fun windComponents(
        speedMps: Double,
        directionDegrees: Double?,
    ): Pair<Double, Double>? {
        require(speedMps.isFinite() && speedMps >= 0.0) {
            "Wind speed must be finite and non-negative"
        }
        if (speedMps == 0.0) {
            return 0.0 to 0.0
        }
        if (directionDegrees == null) {
            return null
        }
        require(directionDegrees.isFinite()) {
            "Wind direction must be finite"
        }

        val radians = Math.toRadians(normalizeDegrees(directionDegrees))
        return (-speedMps * sin(radians)) to (-speedMps * cos(radians))
    }

    private fun normalizeDegrees(value: Double): Double {
        val normalized = value % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }
}
