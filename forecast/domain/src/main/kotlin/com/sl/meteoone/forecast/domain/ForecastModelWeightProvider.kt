package com.sl.meteoone.forecast.domain

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ModelFamily
import java.time.Instant
import java.time.ZoneId

private const val MAXIMUM_MODEL_WEIGHT_RATIO = 1.5
private const val WEIGHT_RATIO_EPSILON = 1e-12

enum class ForecastWeightParameter {
    TEMPERATURE,
    PRESSURE,
    WIND,
    PRECIPITATION,
}

data class ForecastModelWeightRequest(
    val coordinate: ForecastCoordinate,
    val modelRun: Instant,
    val validTime: Instant,
    val timeZoneId: String,
    val parameter: ForecastWeightParameter,
    val modelFamilies: Set<ModelFamily>,
    val evaluatedAt: Instant,
) {
    init {
        require(!validTime.isBefore(modelRun)) {
            "Forecast weight valid time must not precede the model run"
        }
        require(!evaluatedAt.isBefore(modelRun)) {
            "Forecast weight evaluation time must not precede the model run"
        }
        require(modelFamilies.isNotEmpty()) {
            "Forecast weight request requires at least one model family"
        }
        require(ModelFamily.UNKNOWN !in modelFamilies) {
            "Forecast weight request cannot include unknown model family"
        }
        runCatching { ZoneId.of(timeZoneId) }
            .getOrElse { error ->
                throw IllegalArgumentException(
                    "Forecast weight time zone must be a valid ZoneId: $timeZoneId",
                    error,
                )
            }
    }
}

sealed interface ForecastModelWeightDecision {
    data object EqualFallback : ForecastModelWeightDecision

    data class Measured(
        val weights: Map<ModelFamily, Double>,
    ) : ForecastModelWeightDecision {
        init {
            require(weights.isNotEmpty()) {
                "Measured forecast weights must not be empty"
            }
            require(ModelFamily.UNKNOWN !in weights) {
                "Measured forecast weights cannot include unknown model family"
            }
            require(weights.values.all { it.isFinite() && it > 0.0 }) {
                "Measured forecast weights must be finite and positive"
            }
            val weakest = weights.values.min()
            val strongest = weights.values.max()
            require(
                strongest / weakest <= MAXIMUM_MODEL_WEIGHT_RATIO + WEIGHT_RATIO_EPSILON,
            ) {
                "Measured forecast weights exceed the M4 1.5x safety cap"
            }
        }
    }
}

fun interface ForecastModelWeightProvider {
    fun weights(request: ForecastModelWeightRequest): ForecastModelWeightDecision
}

object EqualForecastModelWeightProvider : ForecastModelWeightProvider {
    override fun weights(request: ForecastModelWeightRequest): ForecastModelWeightDecision =
        ForecastModelWeightDecision.EqualFallback
}
