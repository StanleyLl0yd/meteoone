package com.sl.meteoone.verification.domain

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class VerificationAggregationTest {
    private val station = ObservationStation(
        sourceId = "noaa-ncei-ghcnh",
        stationId = "RSM00026063",
        latitude = 59.9667,
        longitude = 30.3,
        elevationMeters = 4.0,
    )
    private val modelRun = Instant.parse("2026-09-20T06:00:00Z")
    private val validTime = Instant.parse("2026-09-20T12:00:00Z")

    @Test
    fun fiveDegreeRegionKeyHasDeterministicBoundaries() {
        val spb = VerificationRegionKey.from(ForecastCoordinate(59.9, 30.3))
        assertEquals(55, spb.southLatitudeDegrees)
        assertEquals(60, spb.northLatitudeDegrees)
        assertEquals(30, spb.westLongitudeDegrees)
        assertEquals(35, spb.eastLongitudeDegrees)

        assertEquals(
            60,
            VerificationRegionKey.from(ForecastCoordinate(60.0, 30.3))
                .southLatitudeDegrees,
        )
        assertEquals(
            -5,
            VerificationRegionKey.from(ForecastCoordinate(-0.1, 0.0))
                .southLatitudeDegrees,
        )
        assertEquals(
            0,
            VerificationRegionKey.from(ForecastCoordinate(0.0, 0.0))
                .southLatitudeDegrees,
        )
        assertEquals(
            85,
            VerificationRegionKey.from(ForecastCoordinate(90.0, 0.0))
                .southLatitudeDegrees,
        )
        assertEquals(
            -180,
            VerificationRegionKey.from(ForecastCoordinate(0.0, -180.0))
                .westLongitudeDegrees,
        )
        assertEquals(
            175,
            VerificationRegionKey.from(ForecastCoordinate(0.0, 179.9))
                .westLongitudeDegrees,
        )
    }

    @Test
    fun duplicateProviderPathsCollapseToOneModelFamilySampleButRemainDiagnostic() {
        val samples = listOf(
            scalar(
                provider = ForecastProvider.OPEN_METEO,
                predicted = 12.0,
                observed = 10.0,
            ),
            scalar(
                provider = ForecastProvider.ECMWF_OPEN_DATA,
                predicted = 14.0,
                observed = 10.0,
            ),
        )

        val aggregates = VerificationSkillAggregator.aggregate(samples)
        assertEquals(2, aggregates.size)

        val location = aggregates.single {
            it.key.scope is VerificationAggregationScope.Location
        }
        assertEquals(ModelFamily.ECMWF_IFS, location.key.modelFamily)
        assertEquals(1, location.independentSampleCount)
        assertEquals(1, location.coverage.distinctRunCount)
        assertEquals(1, location.coverage.distinctCoordinateCount)
        assertEquals(3.0, location.scalar?.bias)
        assertEquals(3.0, location.scalar?.mae)
        assertEquals(3.0, location.scalar?.rmse)

        assertEquals(
            listOf(ForecastProvider.OPEN_METEO, ForecastProvider.ECMWF_OPEN_DATA),
            location.providerDiagnostics.map { it.provider },
        )
        assertEquals(2.0, location.providerDiagnostics[0].scalar?.bias)
        assertEquals(4.0, location.providerDiagnostics[1].scalar?.bias)
    }

    @Test
    fun identicalProviderRerunIsIdempotentButConflictingRerunFailsClosed() {
        val sample = scalar(
            provider = ForecastProvider.OPEN_METEO,
            predicted = 12.0,
            observed = 10.0,
        )
        val aggregate = VerificationSkillAggregator.aggregate(listOf(sample, sample))
            .single { it.key.scope is VerificationAggregationScope.Location }
        assertEquals(1, aggregate.independentSampleCount)
        assertEquals(
            1,
            aggregate.providerDiagnostics.single().coverage.sampleCount,
        )

        assertFailsWith<IllegalArgumentException> {
            VerificationSkillAggregator.aggregate(
                listOf(
                    sample,
                    scalar(
                        provider = ForecastProvider.OPEN_METEO,
                        predicted = 13.0,
                        observed = 10.0,
                    ),
                ),
            )
        }
    }

    @Test
    fun regionAggregateCombinesIndependentCoordinatesAndCarriesCoverage() {
        val nextRun = modelRun.plus(Duration.ofDays(1))
        val nextValid = validTime.plus(Duration.ofDays(1))
        val samples = listOf(
            scalar(
                provider = ForecastProvider.OPEN_METEO,
                predicted = 12.0,
                observed = 10.0,
            ),
            scalar(
                provider = ForecastProvider.OPEN_METEO,
                predicted = 8.0,
                observed = 10.0,
                coordinate = ForecastCoordinate(58.0, 31.0),
                modelRun = nextRun,
                validTime = nextValid,
                observedAt = nextValid,
            ),
        )

        val region = VerificationSkillAggregator.aggregate(samples).single {
            it.key.scope is VerificationAggregationScope.Region
        }
        assertEquals(2, region.independentSampleCount)
        assertEquals(2, region.coverage.distinctRunCount)
        assertEquals(2, region.coverage.distinctCoordinateCount)
        assertEquals(validTime, region.coverage.firstValidTime)
        assertEquals(nextValid, region.coverage.lastValidTime)
        assertEquals(0.0, region.scalar?.bias)
        assertEquals(2.0, region.scalar?.mae)
        assertEquals(2.0, region.scalar?.rmse)
    }

    @Test
    fun scalarAggregationKeepsSeasonParameterAndLeadDimensionsSeparate() {
        val autumn = scalar(
            provider = ForecastProvider.OPEN_METEO,
            predicted = 12.0,
            observed = 10.0,
        )
        val winterRun = Instant.parse("2026-11-30T21:00:00Z")
        val winterValid = Instant.parse("2026-12-01T00:00:00Z")
        val winter = scalar(
            provider = ForecastProvider.OPEN_METEO,
            predicted = 5.0,
            observed = 4.0,
            modelRun = winterRun,
            validTime = winterValid,
            observedAt = winterValid,
        )

        val locations = VerificationSkillAggregator.aggregate(listOf(autumn, winter))
            .filter { it.key.scope is VerificationAggregationScope.Location }
        assertEquals(2, locations.size)
        assertEquals(
            setOf(MeteorologicalSeason.AUTUMN, MeteorologicalSeason.WINTER),
            locations.map { it.key.season }.toSet(),
        )
    }

    @Test
    fun windAggregationUsesComponentBiasAndMeanVectorError() {
        val samples = listOf(
            wind(
                provider = ForecastProvider.OPEN_METEO,
                predictedSpeed = 5.0,
                predictedDirection = 90.0,
                observedSpeed = 5.0,
                observedDirection = 270.0,
            ),
            wind(
                provider = ForecastProvider.ECMWF_OPEN_DATA,
                predictedSpeed = 5.0,
                predictedDirection = 90.0,
                observedSpeed = 5.0,
                observedDirection = 270.0,
            ),
        )

        val location = VerificationSkillAggregator.aggregate(samples).single {
            it.key.scope is VerificationAggregationScope.Location
        }
        assertEquals(1, location.independentSampleCount)
        val wind = assertIs<WindSkillMetrics>(location.wind)
        assertEquals(10.0, wind.meanVectorErrorMps, absoluteTolerance = 1e-12)
        assertEquals(
            10.0,
            kotlin.math.abs(wind.meanUErrorMps),
            absoluteTolerance = 1e-12,
        )
        assertEquals(0.0, wind.meanVErrorMps, absoluteTolerance = 1e-12)
    }

    @Test
    fun precipitationUsesScalarMetricsWithoutLosingExactIntervalIdentity() {
        val interval = ForecastInterval(
            start = validTime.minus(Duration.ofHours(3)),
            end = validTime,
        )
        val sample = PrecipitationVerificationSample(
            context = context(ForecastProvider.OPEN_METEO),
            station = station,
            interval = interval,
            predictedMm = 2.5,
            observedMm = 1.0,
        )

        val location = VerificationSkillAggregator.aggregate(listOf(sample)).single {
            it.key.scope is VerificationAggregationScope.Location
        }
        assertEquals(VerificationParameter.PRECIPITATION, location.key.parameter)
        assertEquals(1.5, location.scalar?.bias)
        assertEquals(interval.end, location.coverage.lastObservedAt)
    }

    private fun scalar(
        provider: ForecastProvider,
        predicted: Double,
        observed: Double,
        coordinate: ForecastCoordinate = ForecastCoordinate(59.9, 30.3),
        modelRun: Instant = this.modelRun,
        validTime: Instant = this.validTime,
        observedAt: Instant = validTime,
    ): ScalarVerificationSample = ScalarVerificationSample(
        context = context(
            provider = provider,
            coordinate = coordinate,
            modelRun = modelRun,
            validTime = validTime,
        ),
        station = station,
        parameter = VerificationParameter.TEMPERATURE,
        observedAt = observedAt,
        predicted = predicted,
        observed = observed,
    )

    private fun wind(
        provider: ForecastProvider,
        predictedSpeed: Double,
        predictedDirection: Double?,
        observedSpeed: Double,
        observedDirection: Double?,
    ): WindVerificationSample = WindVerificationSample(
        context = context(provider),
        station = station,
        observedAt = validTime,
        predictedSpeedMps = predictedSpeed,
        predictedDirectionDegrees = predictedDirection,
        observedSpeedMps = observedSpeed,
        observedDirectionDegrees = observedDirection,
    )

    private fun context(
        provider: ForecastProvider,
        coordinate: ForecastCoordinate = ForecastCoordinate(59.9, 30.3),
        modelRun: Instant = this.modelRun,
        validTime: Instant = this.validTime,
    ): VerificationContext = VerificationContext(
        coordinate = coordinate,
        provider = provider,
        modelFamily = ModelFamily.ECMWF_IFS,
        modelRun = modelRun,
        validTime = validTime,
        timeZoneId = "Europe/Moscow",
    )
}
