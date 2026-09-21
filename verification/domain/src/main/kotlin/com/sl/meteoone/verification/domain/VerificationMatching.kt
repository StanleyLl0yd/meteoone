package com.sl.meteoone.verification.domain

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.time.Duration
import java.time.Instant

private val DEFAULT_SURFACE_OBSERVATION_TOLERANCE: Duration = Duration.ofMinutes(30)
private val MAX_SURFACE_OBSERVATION_TOLERANCE: Duration = Duration.ofMinutes(30)

data class ForecastVerificationPointEvidence(
    val validTime: Instant,
    val temperatureC: Double?,
    val pressureSeaLevelHpa: Double?,
    val windSpeedMps: Double?,
    val windDirectionDegrees: Double?,
    val precipitationMm: Double?,
    val precipitationInterval: ForecastInterval?,
) {
    init {
        requireFiniteOrNull(temperatureC, "forecast temperature")
        requireFiniteOrNull(pressureSeaLevelHpa, "forecast sea-level pressure")
        requireFiniteOrNull(windSpeedMps, "forecast wind speed")
        requireFiniteOrNull(windDirectionDegrees, "forecast wind direction")
        requireFiniteOrNull(precipitationMm, "forecast precipitation")
        require(windSpeedMps == null || windSpeedMps >= 0.0) {
            "Verification forecast wind speed must not be negative"
        }
        require(precipitationMm == null || precipitationMm >= 0.0) {
            "Verification forecast precipitation must not be negative"
        }
        require(precipitationInterval == null || precipitationMm != null) {
            "Verification forecast precipitation interval requires an amount"
        }
        require(precipitationInterval == null || precipitationInterval.end == validTime) {
            "Verification forecast precipitation interval must end at valid time"
        }
    }
}

data class ForecastVerificationRunEvidence(
    val coordinate: ForecastCoordinate,
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val modelRun: Instant,
    val timeZoneId: String,
    val hourly: List<ForecastVerificationPointEvidence>,
) {
    init {
        require(provider != ForecastProvider.UNKNOWN) {
            "Verification forecast provider must be known"
        }
        require(modelFamily != ModelFamily.UNKNOWN) {
            "Verification forecast model family must be known"
        }
        require(hourly.isNotEmpty()) {
            "Verification forecast evidence must contain hourly points"
        }
        require(hourly.zipWithNext().all { (previous, next) ->
            previous.validTime.isBefore(next.validTime)
        }) {
            "Verification forecast evidence timestamps must be strictly increasing and unique"
        }
        hourly.forEach { point ->
            contextAt(point.validTime)
        }
    }

    fun contextAt(validTime: Instant): VerificationContext = VerificationContext(
        coordinate = coordinate,
        provider = provider,
        modelFamily = modelFamily,
        modelRun = modelRun,
        validTime = validTime,
        timeZoneId = timeZoneId,
    )
}

class VerificationSampleMatcher(
    private val surfaceObservationTolerance: Duration = DEFAULT_SURFACE_OBSERVATION_TOLERANCE,
) {
    init {
        require(!surfaceObservationTolerance.isNegative) {
            "Surface observation tolerance must not be negative"
        }
        require(surfaceObservationTolerance <= MAX_SURFACE_OBSERVATION_TOLERANCE) {
            "Surface observation tolerance must not exceed 30 minutes"
        }
    }

    fun match(
        forecast: ForecastVerificationRunEvidence,
        station: ObservationStation,
        surfaceObservations: List<SurfaceObservation>,
        precipitationObservations: List<PrecipitationObservation>,
    ): List<VerificationSample> {
        validateObservationSeries(station, surfaceObservations, precipitationObservations)
        val sortedSurface = surfaceObservations.sortedBy(SurfaceObservation::observedAt)
        val precipitationByInterval = precipitationObservations.associateBy(
            PrecipitationObservation::interval,
        )

        return buildList {
            forecast.hourly.forEach { point ->
                val context = forecast.contextAt(point.validTime)
                val observed = nearestSurfaceObservation(
                    validTime = point.validTime,
                    observations = sortedSurface,
                    tolerance = surfaceObservationTolerance,
                )
                if (observed != null) {
                    point.temperatureC?.let { predicted ->
                        observed.temperatureC?.let { actual ->
                            add(
                                ScalarVerificationSample(
                                    context = context,
                                    station = station,
                                    parameter = VerificationParameter.TEMPERATURE,
                                    observedAt = observed.observedAt,
                                    predicted = predicted,
                                    observed = actual,
                                ),
                            )
                        }
                    }
                    point.pressureSeaLevelHpa?.let { predicted ->
                        observed.pressureSeaLevelHpa?.let { actual ->
                            add(
                                ScalarVerificationSample(
                                    context = context,
                                    station = station,
                                    parameter = VerificationParameter.PRESSURE,
                                    observedAt = observed.observedAt,
                                    predicted = predicted,
                                    observed = actual,
                                ),
                            )
                        }
                    }

                    val predictedWindSpeed = point.windSpeedMps
                    val observedWindSpeed = observed.windSpeedMps
                    if (
                        predictedWindSpeed != null &&
                        observedWindSpeed != null &&
                        VerificationMetrics.windVectorError(
                            predictedSpeedMps = predictedWindSpeed,
                            predictedDirectionDegrees = point.windDirectionDegrees,
                            observedSpeedMps = observedWindSpeed,
                            observedDirectionDegrees = observed.windDirectionDegrees,
                        ) != null
                    ) {
                        add(
                            WindVerificationSample(
                                context = context,
                                station = station,
                                observedAt = observed.observedAt,
                                predictedSpeedMps = predictedWindSpeed,
                                predictedDirectionDegrees = point.windDirectionDegrees,
                                observedSpeedMps = observedWindSpeed,
                                observedDirectionDegrees = observed.windDirectionDegrees,
                            ),
                        )
                    }
                }

                val predictedPrecipitation = point.precipitationMm
                val predictedInterval = point.precipitationInterval
                if (predictedPrecipitation != null && predictedInterval != null) {
                    precipitationByInterval[predictedInterval]?.let { observedPrecipitation ->
                        add(
                            PrecipitationVerificationSample(
                                context = context,
                                station = station,
                                interval = predictedInterval,
                                predictedMm = predictedPrecipitation,
                                observedMm = observedPrecipitation.amountMm,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun validateObservationSeries(
    station: ObservationStation,
    surfaceObservations: List<SurfaceObservation>,
    precipitationObservations: List<PrecipitationObservation>,
) {
    require(surfaceObservations.all { it.station == station }) {
        "Verification surface observation station does not match series station"
    }
    require(precipitationObservations.all { it.station == station }) {
        "Verification precipitation observation station does not match series station"
    }
    require(surfaceObservations.map(SurfaceObservation::observedAt).distinct().size == surfaceObservations.size) {
        "Verification surface observations contain duplicate timestamps"
    }
    require(
        precipitationObservations.map(PrecipitationObservation::interval).distinct().size ==
            precipitationObservations.size,
    ) {
        "Verification precipitation observations contain duplicate intervals"
    }
}

private fun nearestSurfaceObservation(
    validTime: Instant,
    observations: List<SurfaceObservation>,
    tolerance: Duration,
): SurfaceObservation? {
    if (observations.isEmpty()) return null

    var low = 0
    var high = observations.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (observations[middle].observedAt.isBefore(validTime)) {
            low = middle + 1
        } else {
            high = middle
        }
    }

    var best: SurfaceObservation? = null
    var bestDelta: Duration? = null

    fun consider(index: Int) {
        if (index !in observations.indices) return
        val candidate = observations[index]
        val delta = Duration.between(validTime, candidate.observedAt).abs()
        val current = best
        val currentDelta = bestDelta
        if (
            current == null ||
            currentDelta == null ||
            delta < currentDelta ||
            (delta == currentDelta && candidate.observedAt.isBefore(current.observedAt))
        ) {
            best = candidate
            bestDelta = delta
        }
    }

    consider(low)
    consider(low - 1)

    val delta = bestDelta ?: return null
    return if (delta <= tolerance) best else null
}

private fun requireFiniteOrNull(value: Double?, label: String) {
    require(value == null || value.isFinite()) {
        "$label must be finite when present"
    }
}
