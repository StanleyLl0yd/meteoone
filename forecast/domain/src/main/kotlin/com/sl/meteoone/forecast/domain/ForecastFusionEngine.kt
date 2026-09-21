package com.sl.meteoone.forecast.domain

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.model.WeatherCondition
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val DIRECTION_VECTOR_EPSILON = 1e-12
private const val CALM_WIND_EPSILON_MPS = 1e-9

class ForecastFusionEngine(
    private val weightProvider: ForecastModelWeightProvider = EqualForecastModelWeightProvider,
) {
    fun fuse(sources: List<SourceForecast>): FusedForecast {
        require(sources.isNotEmpty())

        val location = sources.first().location
        require(sources.all { it.location == location })

        val evaluatedAt = sources.maxOf { it.origin.generatedAt }
        val coordinate = runCatching {
            ForecastCoordinate(location.latitude, location.longitude)
        }.getOrNull()

        val pointsByTime = buildMap<Instant, MutableList<SourcePoint>> {
            sources.forEach { source ->
                source.hourly.forEach { point ->
                    getOrPut(point.time) { mutableListOf() }
                        .add(SourcePoint(source.origin, point))
                }
            }
        }

        val hourly = pointsByTime
            .toSortedMap()
            .map { (_, sourcePoints) ->
                fuseHour(
                    points = sourcePoints,
                    coordinate = coordinate,
                    timeZoneId = location.timeZoneId,
                    evaluatedAt = evaluatedAt,
                )
            }

        return FusedForecast(
            location = location,
            generatedAt = evaluatedAt,
            hourly = hourly,
        )
    }

    private fun fuseHour(
        points: List<SourcePoint>,
        coordinate: ForecastCoordinate?,
        timeZoneId: String,
        evaluatedAt: Instant,
    ): FusedHourlyForecast {
        val evidenceGroups = points.groupBy { evidenceKey(it.origin) }
        val first = points.first().point

        fun collapsedScalar(
            selector: (HourlyWeatherPoint) -> Double?,
        ): List<ScalarEvidence> = evidenceGroups.mapNotNull { (key, group) ->
            collapseScalarEvidence(
                key = key,
                group = group,
                selector = selector,
            )
        }

        fun fuseScalar(
            parameter: ForecastWeightParameter? = null,
            selector: (HourlyWeatherPoint) -> Double?,
        ): Double? = fuseEvidenceScalar(
            evidence = collapsedScalar(selector),
            parameter = parameter,
            coordinate = coordinate,
            validTime = first.time,
            timeZoneId = timeZoneId,
            evaluatedAt = evaluatedAt,
        )

        val temperatureEvidence = collapsedScalar { it.temperatureC }
        val temperatureValues = temperatureEvidence.map(ScalarEvidence::baselineValue)
        val temperature = fuseEvidenceScalar(
            evidence = temperatureEvidence,
            parameter = ForecastWeightParameter.TEMPERATURE,
            coordinate = coordinate,
            validTime = first.time,
            timeZoneId = timeZoneId,
            evaluatedAt = evaluatedAt,
        )
        val wind = fuseWind(
            evidenceGroups = evidenceGroups,
            coordinate = coordinate,
            validTime = first.time,
            timeZoneId = timeZoneId,
            evaluatedAt = evaluatedAt,
        )
        val windGust = fuseIntervalScalar(
            evidenceGroups = evidenceGroups,
            valueSelector = { it.windGustMps },
            intervalSelector = { it.windGustInterval },
            parameter = null,
            coordinate = coordinate,
            validTime = first.time,
            timeZoneId = timeZoneId,
            evaluatedAt = evaluatedAt,
        )
        val precipitation = fuseIntervalScalar(
            evidenceGroups = evidenceGroups,
            valueSelector = { it.precipitationMm },
            intervalSelector = { it.precipitationInterval },
            parameter = ForecastWeightParameter.PRECIPITATION,
            coordinate = coordinate,
            validTime = first.time,
            timeZoneId = timeZoneId,
            evaluatedAt = evaluatedAt,
        )

        val weather = HourlyWeatherPoint(
            time = first.time,
            temperatureC = temperature,
            feelsLikeC = fuseScalar { it.feelsLikeC },
            dewPointC = fuseScalar { it.dewPointC },
            humidityPercent = fuseScalar { it.humidityPercent },
            pressureSeaLevelHpa = fuseScalar(ForecastWeightParameter.PRESSURE) {
                it.pressureSeaLevelHpa
            },
            windSpeedMps = wind.speedMps,
            windGustMps = windGust.value,
            windDirectionDegrees = wind.directionDegrees,
            precipitationMm = precipitation.value,
            precipitationProbabilityPercent = fuseScalar { it.precipitationProbabilityPercent },
            cloudCoverPercent = fuseScalar { it.cloudCoverPercent },
            visibilityMeters = fuseScalar { it.visibilityMeters },
            condition = fuseCondition(evidenceGroups.values),
            windGustInterval = windGust.interval,
            precipitationInterval = precipitation.interval,
        )

        return FusedHourlyForecast(
            weather = weather,
            providerCount = points.map { it.origin.provider }.distinct().size,
            independentEvidenceCount = evidenceGroups.size,
            agreement = temperatureAgreement(temperatureValues),
        )
    }

    private fun collapseScalarEvidence(
        key: EvidenceKey,
        group: List<SourcePoint>,
        selector: (HourlyWeatherPoint) -> Double?,
    ): ScalarEvidence? {
        val available = group.mapNotNull { sourcePoint ->
            selector(sourcePoint.point)?.let { value -> ScalarSourceValue(sourcePoint, value) }
        }
        val baselineValue = median(available.map(ScalarSourceValue::value)) ?: return null

        val exact = available.filter { it.sourcePoint.origin.modelRun != null }
        val exactRuns = exact.map {
            requireNotNull(it.sourcePoint.origin.modelRun)
        }.distinct()
        val exactRun = exactRuns.singleOrNull()
        val exactValue = exactRun?.let { run ->
            median(
                exact
                    .filter { it.sourcePoint.origin.modelRun == run }
                    .map(ScalarSourceValue::value),
            )
        }

        return ScalarEvidence(
            key = key,
            baselineValue = baselineValue,
            exactValue = exactValue,
            exactRun = exactRun,
        )
    }

    private fun fuseEvidenceScalar(
        evidence: List<ScalarEvidence>,
        parameter: ForecastWeightParameter?,
        coordinate: ForecastCoordinate?,
        validTime: Instant,
        timeZoneId: String,
        evaluatedAt: Instant,
    ): Double? {
        if (evidence.isEmpty()) return null
        val baseline = evidence.map(ScalarEvidence::baselineValue).average()
        if (parameter == null || coordinate == null) return baseline

        val weights = measuredWeights(
            parameter = parameter,
            evidence = evidence.mapNotNull(ScalarEvidence::measuredIdentityOrNull),
            coordinate = coordinate,
            validTime = validTime,
            timeZoneId = timeZoneId,
            evaluatedAt = evaluatedAt,
        ) ?: return baseline

        return weightedScalarAverage(evidence, weights) ?: baseline
    }

    private fun fuseWind(
        evidenceGroups: Map<EvidenceKey, List<SourcePoint>>,
        coordinate: ForecastCoordinate?,
        validTime: Instant,
        timeZoneId: String,
        evaluatedAt: Instant,
    ): WindFusion {
        val evidence = evidenceGroups.mapNotNull { (key, group) ->
            collapseWindEvidence(key, group)
        }
        val baseline = WindFusion(
            speedMps = evidence
                .map(WindEvidence::baselineSpeedMps)
                .takeIf { it.isNotEmpty() }
                ?.average(),
            directionDegrees = circularMeanDegrees(
                evidence.mapNotNull(WindEvidence::baselineDirectionDegrees),
            ),
        )
        if (coordinate == null || evidence.size < 2) return baseline

        val weights = measuredWeights(
            parameter = ForecastWeightParameter.WIND,
            evidence = evidence.mapNotNull(WindEvidence::measuredIdentityOrNull),
            coordinate = coordinate,
            validTime = validTime,
            timeZoneId = timeZoneId,
            evaluatedAt = evaluatedAt,
        ) ?: return baseline

        val selected = evidence.map { item ->
            val family = item.key.modelFamily
            val measuredWeight = family?.let(weights::get)
            if (measuredWeight != null) {
                val vector = item.exactVector ?: return baseline
                WeightedVector(vector, measuredWeight)
            } else {
                val vector = item.baselineVector ?: return baseline
                WeightedVector(vector, 1.0)
            }
        }
        val denominator = selected.sumOf(WeightedVector::weight)
        if (denominator <= 0.0 || !denominator.isFinite()) return baseline

        val u = selected.sumOf { it.vector.uMps * it.weight } / denominator
        val v = selected.sumOf { it.vector.vMps * it.weight } / denominator
        val speed = hypot(u, v)
        if (speed <= DIRECTION_VECTOR_EPSILON) {
            return WindFusion(speedMps = 0.0, directionDegrees = null)
        }
        return WindFusion(
            speedMps = speed,
            directionDegrees = normalizeDegrees(Math.toDegrees(atan2(-u, -v))),
        )
    }

    private fun collapseWindEvidence(
        key: EvidenceKey,
        group: List<SourcePoint>,
    ): WindEvidence? {
        val baselineSpeed = median(group.mapNotNull { it.point.windSpeedMps }) ?: return null
        if (baselineSpeed < 0.0) return null
        val baselineDirection = circularMeanDegrees(
            group.mapNotNull { it.point.windDirectionDegrees },
        )
        val baselineVector = meteorologicalWindVector(
            speedMps = baselineSpeed,
            directionDegrees = baselineDirection,
        )

        val exactWithSpeed = group.filter {
            it.origin.modelRun != null && it.point.windSpeedMps != null
        }
        val exactRuns = exactWithSpeed.map {
            requireNotNull(it.origin.modelRun)
        }.distinct()
        val exactRun = exactRuns.singleOrNull()
        val exactSubset = exactRun?.let { run ->
            exactWithSpeed.filter { it.origin.modelRun == run }
        }.orEmpty()
        val exactSpeed = median(exactSubset.mapNotNull { it.point.windSpeedMps })
        val exactDirection = circularMeanDegrees(
            exactSubset.mapNotNull { it.point.windDirectionDegrees },
        )
        val exactVector = exactSpeed?.let { speed ->
            if (speed < 0.0) null else meteorologicalWindVector(speed, exactDirection)
        }

        return WindEvidence(
            key = key,
            baselineSpeedMps = baselineSpeed,
            baselineDirectionDegrees = baselineDirection,
            baselineVector = baselineVector,
            exactVector = exactVector,
            exactRun = exactRun.takeIf { exactVector != null },
        )
    }

    private fun fuseIntervalScalar(
        evidenceGroups: Map<EvidenceKey, List<SourcePoint>>,
        valueSelector: (HourlyWeatherPoint) -> Double?,
        intervalSelector: (HourlyWeatherPoint) -> ForecastInterval?,
        parameter: ForecastWeightParameter?,
        coordinate: ForecastCoordinate?,
        validTime: Instant,
        timeZoneId: String,
        evaluatedAt: Instant,
    ): IntervalScalarFusion {
        val candidates = buildSet {
            evidenceGroups.values.forEach { group ->
                group.forEach { sourcePoint ->
                    if (valueSelector(sourcePoint.point) != null) {
                        add(IntervalKey(intervalSelector(sourcePoint.point)))
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return IntervalScalarFusion(value = null, interval = null)
        }

        val ranked = candidates.map { key ->
            IntervalCandidate(
                key = key,
                independentEvidenceSupport = evidenceGroups.values.count { group ->
                    group.any { sourcePoint ->
                        valueSelector(sourcePoint.point) != null &&
                            intervalSelector(sourcePoint.point) == key.interval
                    }
                },
            )
        }.sortedWith(
            Comparator { left, right -> compareIntervalCandidates(left, right) },
        )

        val selected = ranked.first().key.interval
        val evidence = evidenceGroups.mapNotNull { (key, group) ->
            collapseScalarEvidence(
                key = key,
                group = group.filter { sourcePoint ->
                    intervalSelector(sourcePoint.point) == selected
                },
                selector = valueSelector,
            )
        }

        return IntervalScalarFusion(
            value = fuseEvidenceScalar(
                evidence = evidence,
                parameter = parameter,
                coordinate = coordinate,
                validTime = validTime,
                timeZoneId = timeZoneId,
                evaluatedAt = evaluatedAt,
            ),
            interval = selected,
        )
    }

    private fun measuredWeights(
        parameter: ForecastWeightParameter,
        evidence: List<MeasuredEvidenceIdentity>,
        coordinate: ForecastCoordinate,
        validTime: Instant,
        timeZoneId: String,
        evaluatedAt: Instant,
    ): Map<ModelFamily, Double>? {
        val eligible = evidence.filter {
            it.key.modelFamily != null &&
                it.key.modelFamily != ModelFamily.UNKNOWN
        }
        if (eligible.size < 2) return null

        val families = eligible.map { requireNotNull(it.key.modelFamily) }.toSet()
        if (families.size != eligible.size) return null
        val modelRuns = eligible.map(MeasuredEvidenceIdentity::modelRun).toSet()
        val modelRun = modelRuns.singleOrNull() ?: return null
        if (validTime.isBefore(modelRun)) return null

        val decision = try {
            weightProvider.weights(
                ForecastModelWeightRequest(
                    coordinate = coordinate,
                    modelRun = modelRun,
                    validTime = validTime,
                    timeZoneId = timeZoneId,
                    parameter = parameter,
                    modelFamilies = families,
                    evaluatedAt = evaluatedAt,
                ),
            )
        } catch (_: Exception) {
            return null
        }

        val weights = when (decision) {
            ForecastModelWeightDecision.EqualFallback -> return null
            is ForecastModelWeightDecision.Measured -> decision.weights
        }
        if (weights.keys != families) return null
        if (weights.values.toSet().size == 1) return null
        return weights
    }

    private fun weightedScalarAverage(
        evidence: List<ScalarEvidence>,
        weights: Map<ModelFamily, Double>,
    ): Double? {
        var numerator = 0.0
        var denominator = 0.0
        evidence.forEach { item ->
            val family = item.key.modelFamily
            val measuredWeight = family?.let(weights::get)
            if (measuredWeight != null) {
                val exactValue = item.exactValue ?: return null
                numerator += exactValue * measuredWeight
                denominator += measuredWeight
            } else {
                numerator += item.baselineValue
                denominator += 1.0
            }
        }
        if (denominator <= 0.0 || !denominator.isFinite()) return null
        val result = numerator / denominator
        return result.takeIf(Double::isFinite)
    }

    private fun ScalarEvidence.measuredIdentityOrNull(): MeasuredEvidenceIdentity? =
        if (exactValue != null && exactRun != null) {
            MeasuredEvidenceIdentity(key = key, modelRun = exactRun)
        } else {
            null
        }

    private fun WindEvidence.measuredIdentityOrNull(): MeasuredEvidenceIdentity? =
        if (exactVector != null && exactRun != null) {
            MeasuredEvidenceIdentity(key = key, modelRun = exactRun)
        } else {
            null
        }

    private fun meteorologicalWindVector(
        speedMps: Double,
        directionDegrees: Double?,
    ): WindVector? {
        if (speedMps <= CALM_WIND_EPSILON_MPS) {
            return WindVector(uMps = 0.0, vMps = 0.0)
        }
        if (directionDegrees == null) return null
        val radians = Math.toRadians(normalizeDegrees(directionDegrees))
        return WindVector(
            uMps = -speedMps * sin(radians),
            vMps = -speedMps * cos(radians),
        )
    }

    private fun compareIntervalCandidates(
        left: IntervalCandidate,
        right: IntervalCandidate,
    ): Int {
        val support = right.independentEvidenceSupport.compareTo(left.independentEvidenceSupport)
        if (support != 0) return support

        val leftInterval = left.key.interval
        val rightInterval = right.key.interval
        if ((leftInterval == null) != (rightInterval == null)) {
            return if (leftInterval != null) -1 else 1
        }
        if (leftInterval != null && rightInterval != null) {
            val duration = leftInterval.duration.compareTo(rightInterval.duration)
            if (duration != 0) return duration

            val start = rightInterval.start.compareTo(leftInterval.start)
            if (start != 0) return start
        }
        return 0
    }

    private fun fuseCondition(
        evidenceGroups: Collection<List<SourcePoint>>,
    ): WeatherCondition {
        val votes = evidenceGroups.mapNotNull { group ->
            group
                .map { it.point.condition }
                .filter { it != WeatherCondition.UNKNOWN }
                .distinct()
                .singleOrNull()
        }
        if (votes.isEmpty()) {
            return WeatherCondition.UNKNOWN
        }

        val counts = votes.groupingBy { it }.eachCount()
        val highestCount = counts.values.max()
        return counts.entries
            .singleOrNull { it.value == highestCount }
            ?.key
            ?: WeatherCondition.UNKNOWN
    }

    private fun evidenceKey(origin: ForecastOrigin): EvidenceKey =
        if (origin.modelFamily == ModelFamily.UNKNOWN) {
            EvidenceKey(provider = origin.provider)
        } else {
            EvidenceKey(modelFamily = origin.modelFamily)
        }

    private fun temperatureAgreement(values: List<Double>): ModelAgreement {
        if (values.size < 2) {
            return ModelAgreement.INSUFFICIENT
        }

        val spread = values.max() - values.min()
        return when {
            spread <= 1.5 -> ModelAgreement.HIGH
            spread <= 3.0 -> ModelAgreement.MEDIUM
            else -> ModelAgreement.LOW
        }
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) {
            return null
        }

        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun circularMeanDegrees(values: List<Double>): Double? {
        if (values.isEmpty()) {
            return null
        }

        val x = values.sumOf { cos(Math.toRadians(normalizeDegrees(it))) }
        val y = values.sumOf { sin(Math.toRadians(normalizeDegrees(it))) }

        if (hypot(x, y) <= DIRECTION_VECTOR_EPSILON * values.size) {
            return null
        }

        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    private fun normalizeDegrees(value: Double): Double {
        val normalized = value % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }

    private data class SourcePoint(
        val origin: ForecastOrigin,
        val point: HourlyWeatherPoint,
    )

    private data class EvidenceKey(
        val modelFamily: ModelFamily? = null,
        val provider: ForecastProvider? = null,
    )

    private data class ScalarSourceValue(
        val sourcePoint: SourcePoint,
        val value: Double,
    )

    private data class ScalarEvidence(
        val key: EvidenceKey,
        val baselineValue: Double,
        val exactValue: Double?,
        val exactRun: Instant?,
    )

    private data class MeasuredEvidenceIdentity(
        val key: EvidenceKey,
        val modelRun: Instant,
    )

    private data class WindEvidence(
        val key: EvidenceKey,
        val baselineSpeedMps: Double,
        val baselineDirectionDegrees: Double?,
        val baselineVector: WindVector?,
        val exactVector: WindVector?,
        val exactRun: Instant?,
    )

    private data class WindVector(
        val uMps: Double,
        val vMps: Double,
    )

    private data class WeightedVector(
        val vector: WindVector,
        val weight: Double,
    )

    private data class WindFusion(
        val speedMps: Double?,
        val directionDegrees: Double?,
    )

    private data class IntervalKey(
        val interval: ForecastInterval?,
    )

    private data class IntervalCandidate(
        val key: IntervalKey,
        val independentEvidenceSupport: Int,
    )

    private data class IntervalScalarFusion(
        val value: Double?,
        val interval: ForecastInterval?,
    )
}
