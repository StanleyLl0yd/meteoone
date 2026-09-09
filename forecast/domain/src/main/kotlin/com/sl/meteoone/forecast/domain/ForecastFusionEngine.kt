package com.sl.meteoone.forecast.domain

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
import kotlin.math.sin

class ForecastFusionEngine {
    fun fuse(sources: List<SourceForecast>): FusedForecast {
        require(sources.isNotEmpty())

        val location = sources.first().location
        require(sources.all { it.location == location })

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
            .map { (_, sourcePoints) -> fuseHour(sourcePoints) }

        return FusedForecast(
            location = location,
            generatedAt = sources.maxOf { it.origin.generatedAt },
            hourly = hourly,
        )
    }

    private fun fuseHour(points: List<SourcePoint>): FusedHourlyForecast {
        val evidenceGroups = points.groupBy { evidenceKey(it.origin) }

        fun fuseScalar(selector: (HourlyWeatherPoint) -> Double?): Double? {
            val evidenceValues = evidenceGroups.values.mapNotNull { group ->
                median(group.mapNotNull { selector(it.point) })
            }
            return evidenceValues.takeIf { it.isNotEmpty() }?.average()
        }

        val temperatureValues = evidenceGroups.values.mapNotNull { group ->
            median(group.mapNotNull { it.point.temperatureC })
        }

        val windDirection = circularMeanDegrees(
            evidenceGroups.values.mapNotNull { group ->
                circularMeanDegrees(group.mapNotNull { it.point.windDirectionDegrees })
            },
        )

        val first = points.first().point
        val weather = HourlyWeatherPoint(
            time = first.time,
            temperatureC = temperatureValues.takeIf { it.isNotEmpty() }?.average(),
            feelsLikeC = fuseScalar { it.feelsLikeC },
            dewPointC = fuseScalar { it.dewPointC },
            humidityPercent = fuseScalar { it.humidityPercent },
            pressureSeaLevelHpa = fuseScalar { it.pressureSeaLevelHpa },
            windSpeedMps = fuseScalar { it.windSpeedMps },
            windGustMps = fuseScalar { it.windGustMps },
            windDirectionDegrees = windDirection,
            precipitationMm = fuseScalar { it.precipitationMm },
            precipitationProbabilityPercent = fuseScalar { it.precipitationProbabilityPercent },
            cloudCoverPercent = fuseScalar { it.cloudCoverPercent },
            visibilityMeters = fuseScalar { it.visibilityMeters },
            condition = WeatherCondition.UNKNOWN,
        )

        return FusedHourlyForecast(
            weather = weather,
            providerCount = points.map { it.origin.provider }.distinct().size,
            independentEvidenceCount = evidenceGroups.size,
            agreement = temperatureAgreement(temperatureValues),
        )
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

        if (x == 0.0 && y == 0.0) {
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
}
