package com.sl.meteoone.verification.domain

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

private const val REGION_DEGREES = 5
private const val REGION_TENTHS = REGION_DEGREES * 10
private const val LATITUDE_OFFSET_TENTHS = 900
private const val LONGITUDE_OFFSET_TENTHS = 1800
private const val LATITUDE_BAND_COUNT = 36
private const val LONGITUDE_BAND_COUNT = 72

data class VerificationRegionKey(
    val latitudeBand: Int,
    val longitudeBand: Int,
) {
    init {
        require(latitudeBand in 0 until LATITUDE_BAND_COUNT) {
            "Verification latitude region band is out of range"
        }
        require(longitudeBand in 0 until LONGITUDE_BAND_COUNT) {
            "Verification longitude region band is out of range"
        }
    }

    val southLatitudeDegrees: Int
        get() = -90 + latitudeBand * REGION_DEGREES
    val northLatitudeDegrees: Int
        get() = if (latitudeBand == LATITUDE_BAND_COUNT - 1) {
            90
        } else {
            southLatitudeDegrees + REGION_DEGREES
        }
    val westLongitudeDegrees: Int
        get() = -180 + longitudeBand * REGION_DEGREES
    val eastLongitudeDegrees: Int
        get() = westLongitudeDegrees + REGION_DEGREES

    val encoded: String
        get() = "r5-" +
            latitudeBand.toString().padStart(2, '0') +
            "-" +
            longitudeBand.toString().padStart(2, '0')

    companion object {
        fun from(coordinate: ForecastCoordinate): VerificationRegionKey {
            val latitudeTenths = coordinate.latitude.toCanonicalTenths()
            val longitudeTenths = coordinate.longitude.toCanonicalTenths()
            val latitudeBand = ((latitudeTenths + LATITUDE_OFFSET_TENTHS) / REGION_TENTHS)
                .coerceIn(0, LATITUDE_BAND_COUNT - 1)
            val longitudeBand =
                (longitudeTenths + LONGITUDE_OFFSET_TENTHS) / REGION_TENTHS
            return VerificationRegionKey(
                latitudeBand = latitudeBand,
                longitudeBand = longitudeBand,
            )
        }
    }
}

sealed interface VerificationAggregationScope {
    data class Location(
        val coordinate: ForecastCoordinate,
    ) : VerificationAggregationScope

    data class Region(
        val key: VerificationRegionKey,
    ) : VerificationAggregationScope
}

data class VerificationSkillKey(
    val scope: VerificationAggregationScope,
    val season: MeteorologicalSeason,
    val parameter: VerificationParameter,
    val leadBucket: LeadTimeBucket,
    val modelFamily: ModelFamily,
) {
    init {
        require(modelFamily != ModelFamily.UNKNOWN) {
            "Verification skill key requires a known model family"
        }
    }
}

data class VerificationEvidenceCoverage(
    val sampleCount: Int,
    val distinctRunCount: Int,
    val distinctCoordinateCount: Int,
    val firstValidTime: Instant,
    val lastValidTime: Instant,
    val firstObservedAt: Instant,
    val lastObservedAt: Instant,
) {
    init {
        require(sampleCount > 0)
        require(distinctRunCount > 0)
        require(distinctCoordinateCount > 0)
        require(!lastValidTime.isBefore(firstValidTime))
        require(!lastObservedAt.isBefore(firstObservedAt))
    }
}

data class ScalarSkillMetrics(
    val count: Int,
    val bias: Double,
    val mae: Double,
    val rmse: Double,
) {
    init {
        require(count > 0)
        require(bias.isFinite() && mae.isFinite() && rmse.isFinite())
        require(mae >= 0.0 && rmse >= 0.0)
    }
}

data class WindSkillMetrics(
    val count: Int,
    val meanUErrorMps: Double,
    val meanVErrorMps: Double,
    val meanVectorErrorMps: Double,
) {
    init {
        require(count > 0)
        require(
            meanUErrorMps.isFinite() &&
                meanVErrorMps.isFinite() &&
                meanVectorErrorMps.isFinite(),
        )
        require(meanVectorErrorMps >= 0.0)
    }
}

data class VerificationProviderDiagnostic(
    val provider: ForecastProvider,
    val coverage: VerificationEvidenceCoverage,
    val scalar: ScalarSkillMetrics?,
    val wind: WindSkillMetrics?,
) {
    init {
        require(provider != ForecastProvider.UNKNOWN)
        require((scalar == null) != (wind == null)) {
            "Provider diagnostic must contain exactly one metric family"
        }
    }
}

data class VerificationSkillAggregate(
    val key: VerificationSkillKey,
    val coverage: VerificationEvidenceCoverage,
    val scalar: ScalarSkillMetrics?,
    val wind: WindSkillMetrics?,
    val providerDiagnostics: List<VerificationProviderDiagnostic>,
) {
    val independentSampleCount: Int
        get() = coverage.sampleCount

    init {
        require((scalar == null) != (wind == null)) {
            "Verification skill aggregate must contain exactly one metric family"
        }
        require(providerDiagnostics.isNotEmpty())
        require(providerDiagnostics.map { it.provider }.distinct().size == providerDiagnostics.size)
    }
}

object VerificationSkillAggregator {
    fun aggregate(samples: Collection<VerificationSample>): List<VerificationSkillAggregate> {
        if (samples.isEmpty()) return emptyList()

        val providerSamples = deduplicateProviderSamples(samples)
        val independent = providerSamples
            .groupBy { it.modelIdentity() }
            .values
            .map(::collapseProviderPaths)

        val providerIndex = buildProviderDiagnosticIndex(providerSamples)
        val grouped = linkedMapOf<VerificationSkillKey, MutableList<IndependentVerificationError>>()
        independent.forEach { error ->
            error.scopes().forEach { scope ->
                grouped.getOrPut(error.skillKey(scope)) { mutableListOf() }.add(error)
            }
        }

        return grouped
            .map { (key, values) ->
                val diagnostics = providerIndex[key]
                    .orEmpty()
                    .entries
                    .sortedBy { it.key.ordinal }
                    .map { (provider, providerValues) ->
                        providerDiagnostic(provider, providerValues)
                    }
                aggregateGroup(key, values, diagnostics)
            }
            .sortedWith(skillAggregateComparator)
    }
}

private data class ObservationIdentity(
    val sourceId: String,
    val stationId: String,
    val start: Instant?,
    val end: Instant,
)

private data class ProviderSampleIdentity(
    val coordinate: ForecastCoordinate,
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val modelRun: Instant,
    val validTime: Instant,
    val parameter: VerificationParameter,
    val observation: ObservationIdentity,
)

private data class ModelSampleIdentity(
    val coordinate: ForecastCoordinate,
    val modelFamily: ModelFamily,
    val modelRun: Instant,
    val validTime: Instant,
    val parameter: VerificationParameter,
    val observation: ObservationIdentity,
)

private sealed interface IndependentErrorValue {
    data class Scalar(
        val signed: Double,
    ) : IndependentErrorValue

    data class Wind(
        val uErrorMps: Double,
        val vErrorMps: Double,
    ) : IndependentErrorValue
}

private data class IndependentVerificationError(
    val identity: ModelSampleIdentity,
    val season: MeteorologicalSeason,
    val leadBucket: LeadTimeBucket,
    val observedAt: Instant,
    val value: IndependentErrorValue,
) {
    fun scopes(): List<VerificationAggregationScope> = listOf(
        VerificationAggregationScope.Location(identity.coordinate),
        VerificationAggregationScope.Region(VerificationRegionKey.from(identity.coordinate)),
    )

    fun skillKey(scope: VerificationAggregationScope): VerificationSkillKey =
        VerificationSkillKey(
            scope = scope,
            season = season,
            parameter = identity.parameter,
            leadBucket = leadBucket,
            modelFamily = identity.modelFamily,
        )
}

private fun deduplicateProviderSamples(
    samples: Collection<VerificationSample>,
): List<VerificationSample> {
    val unique = linkedMapOf<ProviderSampleIdentity, VerificationSample>()
    samples.forEach { sample ->
        val identity = sample.providerIdentity()
        val existing = unique[identity]
        if (existing == null) {
            unique[identity] = sample
        } else {
            require(existing == sample) {
                "Conflicting verification samples share one provider-path identity"
            }
        }
    }
    return unique.values.toList()
}

private fun VerificationSample.providerIdentity(): ProviderSampleIdentity =
    ProviderSampleIdentity(
        coordinate = context.coordinate,
        provider = context.provider,
        modelFamily = context.modelFamily,
        modelRun = context.modelRun,
        validTime = context.validTime,
        parameter = parameter,
        observation = observationIdentity(),
    )

private fun VerificationSample.modelIdentity(): ModelSampleIdentity =
    ModelSampleIdentity(
        coordinate = context.coordinate,
        modelFamily = context.modelFamily,
        modelRun = context.modelRun,
        validTime = context.validTime,
        parameter = parameter,
        observation = observationIdentity(),
    )

private fun VerificationSample.observationIdentity(): ObservationIdentity = when (this) {
    is PrecipitationVerificationSample -> ObservationIdentity(
        sourceId = station.sourceId,
        stationId = station.stationId,
        start = interval.start,
        end = interval.end,
    )

    is ScalarVerificationSample,
    is WindVerificationSample,
    -> ObservationIdentity(
        sourceId = station.sourceId,
        stationId = station.stationId,
        start = null,
        end = observedAt,
    )
}

private fun collapseProviderPaths(
    samples: List<VerificationSample>,
): IndependentVerificationError {
    require(samples.isNotEmpty())
    val first = samples.first()
    require(samples.all { it.modelIdentity() == first.modelIdentity() })
    require(samples.all { it.context.season == first.context.season })
    require(samples.all { it.context.leadBucket == first.context.leadBucket })
    require(samples.all { it.observedAt == first.observedAt })

    val value = when (first) {
        is ScalarVerificationSample -> {
            val signed = samples.map { sample ->
                require(sample is ScalarVerificationSample)
                require(sample.observed == first.observed) {
                    "Duplicate provider paths disagree on scalar verification truth"
                }
                sample.error.signed
            }
            IndependentErrorValue.Scalar(median(signed))
        }

        is PrecipitationVerificationSample -> {
            val signed = samples.map { sample ->
                require(sample is PrecipitationVerificationSample)
                require(sample.observedMm == first.observedMm) {
                    "Duplicate provider paths disagree on precipitation verification truth"
                }
                sample.error.signed
            }
            IndependentErrorValue.Scalar(median(signed))
        }

        is WindVerificationSample -> {
            val uErrors = samples.map { sample ->
                require(sample is WindVerificationSample)
                require(
                    sample.observedSpeedMps == first.observedSpeedMps &&
                        sample.observedDirectionDegrees == first.observedDirectionDegrees,
                ) {
                    "Duplicate provider paths disagree on wind verification truth"
                }
                sample.error.uErrorMps
            }
            val vErrors = samples.map { sample ->
                require(sample is WindVerificationSample)
                sample.error.vErrorMps
            }
            IndependentErrorValue.Wind(
                uErrorMps = median(uErrors),
                vErrorMps = median(vErrors),
            )
        }
    }

    return IndependentVerificationError(
        identity = first.modelIdentity(),
        season = first.context.season,
        leadBucket = first.context.leadBucket,
        observedAt = first.observedAt,
        value = value,
    )
}

private fun aggregateGroup(
    key: VerificationSkillKey,
    values: List<IndependentVerificationError>,
    diagnostics: List<VerificationProviderDiagnostic>,
): VerificationSkillAggregate {
    require(values.isNotEmpty())
    val scalarValues = values.mapNotNull {
        (it.value as? IndependentErrorValue.Scalar)?.signed
    }
    val windValues = values.mapNotNull {
        it.value as? IndependentErrorValue.Wind
    }
    require((scalarValues.isEmpty()) != (windValues.isEmpty()))
    require(scalarValues.size + windValues.size == values.size)

    return VerificationSkillAggregate(
        key = key,
        coverage = coverageOfIndependent(values),
        scalar = scalarValues.takeIf { it.isNotEmpty() }?.let(::scalarMetrics),
        wind = windValues.takeIf { it.isNotEmpty() }?.let(::windMetrics),
        providerDiagnostics = diagnostics,
    )
}

private fun buildProviderDiagnosticIndex(
    samples: List<VerificationSample>,
): Map<VerificationSkillKey, Map<ForecastProvider, List<VerificationSample>>> {
    val result = linkedMapOf<
        VerificationSkillKey,
        MutableMap<ForecastProvider, MutableList<VerificationSample>>
        >()
    samples.forEach { sample ->
        val scopes = listOf(
            VerificationAggregationScope.Location(sample.context.coordinate),
            VerificationAggregationScope.Region(
                VerificationRegionKey.from(sample.context.coordinate),
            ),
        )
        scopes.forEach { scope ->
            val key = VerificationSkillKey(
                scope = scope,
                season = sample.context.season,
                parameter = sample.parameter,
                leadBucket = sample.context.leadBucket,
                modelFamily = sample.context.modelFamily,
            )
            result
                .getOrPut(key) { linkedMapOf() }
                .getOrPut(sample.context.provider) { mutableListOf() }
                .add(sample)
        }
    }
    return result
}

private fun providerDiagnostic(
    provider: ForecastProvider,
    samples: List<VerificationSample>,
): VerificationProviderDiagnostic {
    require(samples.isNotEmpty())
    val scalarErrors = samples.mapNotNull { sample ->
        when (sample) {
            is ScalarVerificationSample -> sample.error.signed
            is PrecipitationVerificationSample -> sample.error.signed
            is WindVerificationSample -> null
        }
    }
    val windErrors = samples.mapNotNull { sample ->
        (sample as? WindVerificationSample)?.error
    }
    require((scalarErrors.isEmpty()) != (windErrors.isEmpty()))
    require(scalarErrors.size + windErrors.size == samples.size)

    return VerificationProviderDiagnostic(
        provider = provider,
        coverage = coverageOfSamples(samples),
        scalar = scalarErrors.takeIf { it.isNotEmpty() }?.let(::scalarMetrics),
        wind = windErrors.takeIf { it.isNotEmpty() }?.let { errors ->
            windMetrics(
                errors.map {
                    IndependentErrorValue.Wind(
                        uErrorMps = it.uErrorMps,
                        vErrorMps = it.vErrorMps,
                    )
                },
            )
        },
    )
}

private fun scalarMetrics(errors: List<Double>): ScalarSkillMetrics {
    require(errors.isNotEmpty())
    val count = errors.size
    return ScalarSkillMetrics(
        count = count,
        bias = errors.sum() / count,
        mae = errors.sumOf { abs(it) } / count,
        rmse = sqrt(errors.sumOf { it * it } / count),
    )
}

private fun windMetrics(
    errors: List<IndependentErrorValue.Wind>,
): WindSkillMetrics {
    require(errors.isNotEmpty())
    val count = errors.size
    return WindSkillMetrics(
        count = count,
        meanUErrorMps = errors.sumOf { it.uErrorMps } / count,
        meanVErrorMps = errors.sumOf { it.vErrorMps } / count,
        meanVectorErrorMps = errors.sumOf {
            hypot(it.uErrorMps, it.vErrorMps)
        } / count,
    )
}

private fun coverageOfIndependent(
    values: List<IndependentVerificationError>,
): VerificationEvidenceCoverage = VerificationEvidenceCoverage(
    sampleCount = values.size,
    distinctRunCount = values.map { it.identity.modelRun }.distinct().size,
    distinctCoordinateCount = values.map { it.identity.coordinate }.distinct().size,
    firstValidTime = values.minOf { it.identity.validTime },
    lastValidTime = values.maxOf { it.identity.validTime },
    firstObservedAt = values.minOf { it.observedAt },
    lastObservedAt = values.maxOf { it.observedAt },
)

private fun coverageOfSamples(
    samples: List<VerificationSample>,
): VerificationEvidenceCoverage = VerificationEvidenceCoverage(
    sampleCount = samples.size,
    distinctRunCount = samples.map { it.context.modelRun }.distinct().size,
    distinctCoordinateCount = samples.map { it.context.coordinate }.distinct().size,
    firstValidTime = samples.minOf { it.context.validTime },
    lastValidTime = samples.maxOf { it.context.validTime },
    firstObservedAt = samples.minOf { it.observedAt },
    lastObservedAt = samples.maxOf { it.observedAt },
)

private fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}

private fun Double.toCanonicalTenths(): Int =
    BigDecimal.valueOf(this).movePointRight(1).intValueExact()

private val skillAggregateComparator =
    compareBy<VerificationSkillAggregate>(
        { it.key.scope.scopeOrder() },
        { it.key.scope.scopeLatitudeOrder() },
        { it.key.scope.scopeLongitudeOrder() },
        { it.key.season.ordinal },
        { it.key.parameter.ordinal },
        { it.key.leadBucket.ordinal },
        { it.key.modelFamily.ordinal },
    )

private fun VerificationAggregationScope.scopeOrder(): Int = when (this) {
    is VerificationAggregationScope.Location -> 0
    is VerificationAggregationScope.Region -> 1
}

private fun VerificationAggregationScope.scopeLatitudeOrder(): Int = when (this) {
    is VerificationAggregationScope.Location -> coordinate.latitude.toCanonicalTenths()
    is VerificationAggregationScope.Region -> key.latitudeBand
}

private fun VerificationAggregationScope.scopeLongitudeOrder(): Int = when (this) {
    is VerificationAggregationScope.Location -> coordinate.longitude.toCanonicalTenths()
    is VerificationAggregationScope.Region -> key.longitudeBand
}
