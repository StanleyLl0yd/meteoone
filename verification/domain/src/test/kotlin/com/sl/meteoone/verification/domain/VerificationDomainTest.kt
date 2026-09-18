package com.sl.meteoone.verification.domain

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.time.Duration
import java.time.Instant
import java.time.Month
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VerificationDomainTest {
    private val station = ObservationStation(
        sourceId = "WIS2",
        stationId = "0-20000-0-26063",
        latitude = 59.97,
        longitude = 30.30,
        elevationMeters = 4.0,
    )

    @Test
    fun leadBucketsPreserveM0Boundaries() {
        assertEquals(LeadTimeBucket.H0_6, LeadTimeBucket.from(Duration.ZERO))
        assertEquals(LeadTimeBucket.H0_6, LeadTimeBucket.from(Duration.ofHours(6)))
        assertEquals(
            LeadTimeBucket.H6_24,
            LeadTimeBucket.from(Duration.ofHours(6).plusNanos(1)),
        )
        assertEquals(LeadTimeBucket.H6_24, LeadTimeBucket.from(Duration.ofHours(24)))
        assertEquals(
            LeadTimeBucket.H24_48,
            LeadTimeBucket.from(Duration.ofHours(24).plusNanos(1)),
        )
        assertEquals(LeadTimeBucket.H24_48, LeadTimeBucket.from(Duration.ofHours(48)))
        assertEquals(
            LeadTimeBucket.H48_72,
            LeadTimeBucket.from(Duration.ofHours(48).plusNanos(1)),
        )
        assertEquals(LeadTimeBucket.H48_72, LeadTimeBucket.from(Duration.ofHours(72)))
        assertNull(LeadTimeBucket.from(Duration.ofHours(72).plusNanos(1)))
        assertNull(LeadTimeBucket.from(Duration.ofNanos(-1)))
    }

    @Test
    fun meteorologicalSeasonsUseForecastLocalCalendarMonth() {
        for (month in listOf(Month.DECEMBER, Month.JANUARY, Month.FEBRUARY)) {
            assertEquals(MeteorologicalSeason.WINTER, MeteorologicalSeason.from(month))
        }
        for (month in listOf(Month.MARCH, Month.APRIL, Month.MAY)) {
            assertEquals(MeteorologicalSeason.SPRING, MeteorologicalSeason.from(month))
        }
        for (month in listOf(Month.JUNE, Month.JULY, Month.AUGUST)) {
            assertEquals(MeteorologicalSeason.SUMMER, MeteorologicalSeason.from(month))
        }
        for (month in listOf(Month.SEPTEMBER, Month.OCTOBER, Month.NOVEMBER)) {
            assertEquals(MeteorologicalSeason.AUTUMN, MeteorologicalSeason.from(month))
        }
    }

    @Test
    fun verificationContextDerivesLeadAndLocalSeason() {
        val context = context(
            modelRun = Instant.parse("2026-11-30T21:00:00Z"),
            validTime = Instant.parse("2026-12-01T00:00:00Z"),
            timeZoneId = "Europe/Moscow",
        )

        assertEquals(Duration.ofHours(3), context.leadTime)
        assertEquals(LeadTimeBucket.H0_6, context.leadBucket)
        assertEquals(MeteorologicalSeason.WINTER, context.season)
    }

    @Test
    fun verificationContextRejectsUnknownIdentityAndOutOfRangeLead() {
        assertFailsWith<IllegalArgumentException> {
            VerificationContext(
                coordinate = ForecastCoordinate(59.9, 30.3),
                provider = ForecastProvider.UNKNOWN,
                modelFamily = ModelFamily.ECMWF_IFS,
                modelRun = Instant.parse("2026-09-01T00:00:00Z"),
                validTime = Instant.parse("2026-09-01T01:00:00Z"),
                timeZoneId = "UTC",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VerificationContext(
                coordinate = ForecastCoordinate(59.9, 30.3),
                provider = ForecastProvider.ECMWF_OPEN_DATA,
                modelFamily = ModelFamily.UNKNOWN,
                modelRun = Instant.parse("2026-09-01T00:00:00Z"),
                validTime = Instant.parse("2026-09-01T01:00:00Z"),
                timeZoneId = "UTC",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            context(
                modelRun = Instant.parse("2026-09-01T00:00:00Z"),
                validTime = Instant.parse("2026-09-04T00:00:00.000000001Z"),
            )
        }
    }

    @Test
    fun scalarErrorCarriesBiasAbsoluteAndSquaredError() {
        val error = VerificationMetrics.scalarError(
            predicted = 7.5,
            observed = 5.0,
        )

        assertEquals(2.5, error.signed)
        assertEquals(2.5, error.absolute)
        assertEquals(6.25, error.squared)
    }

    @Test
    fun windErrorUsesMeteorologicalVectors() {
        val error = requireNotNull(
            VerificationMetrics.windVectorError(
                predictedSpeedMps = 5.0,
                predictedDirectionDegrees = 90.0,
                observedSpeedMps = 5.0,
                observedDirectionDegrees = 270.0,
            ),
        )

        assertEquals(10.0, error.magnitudeMps, absoluteTolerance = 1e-12)
        assertEquals(10.0, abs(error.uErrorMps), absoluteTolerance = 1e-12)
        assertEquals(0.0, error.vErrorMps, absoluteTolerance = 1e-12)
    }

    @Test
    fun calmWindDoesNotRequireDirection() {
        val error = requireNotNull(
            VerificationMetrics.windVectorError(
                predictedSpeedMps = 0.0,
                predictedDirectionDegrees = null,
                observedSpeedMps = 0.0,
                observedDirectionDegrees = null,
            ),
        )

        assertEquals(0.0, error.magnitudeMps)
        assertNull(
            VerificationMetrics.windVectorError(
                predictedSpeedMps = 5.0,
                predictedDirectionDegrees = null,
                observedSpeedMps = 5.0,
                observedDirectionDegrees = 90.0,
            ),
        )
    }

    @Test
    fun precipitationRequiresExactIntervalSemantics() {
        val expected = ForecastInterval(
            start = Instant.parse("2026-09-01T00:00:00Z"),
            end = Instant.parse("2026-09-01T01:00:00Z"),
        )
        val observation = PrecipitationObservation(
            station = station,
            interval = expected,
            amountMm = 1.5,
        )

        assertEquals(
            0.5,
            requireNotNull(
                VerificationMetrics.precipitationError(
                    predictedAmountMm = 2.0,
                    predictedInterval = expected,
                    observed = observation,
                ),
            ).signed,
        )

        assertNull(
            VerificationMetrics.precipitationError(
                predictedAmountMm = 2.0,
                predictedInterval = ForecastInterval(
                    start = Instant.parse("2026-08-31T23:00:00Z"),
                    end = Instant.parse("2026-09-01T01:00:00Z"),
                ),
                observed = observation,
            ),
        )
    }

    @Test
    fun observationModelsRejectNonFiniteOrPhysicallyInvalidValues() {
        assertFailsWith<IllegalArgumentException> {
            SurfaceObservation(
                station = station,
                observedAt = Instant.EPOCH,
                temperatureC = Double.NaN,
                pressureSeaLevelHpa = null,
                windSpeedMps = null,
                windDirectionDegrees = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SurfaceObservation(
                station = station,
                observedAt = Instant.EPOCH,
                temperatureC = null,
                pressureSeaLevelHpa = null,
                windSpeedMps = -1.0,
                windDirectionDegrees = 90.0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PrecipitationObservation(
                station = station,
                interval = ForecastInterval(
                    start = Instant.EPOCH,
                    end = Instant.EPOCH.plusSeconds(3600),
                ),
                amountMm = -0.1,
            )
        }
    }

    @Test
    fun verificationSampleKeepsProviderAndModelFamilyProvenance() {
        val context = context()
        val error = VerificationMetrics.scalarError(3.0, 2.0)
        val sample = ScalarVerificationSample(
            context = context,
            station = station,
            parameter = VerificationParameter.TEMPERATURE,
            predicted = 3.0,
            observed = 2.0,
            error = error,
        )

        assertEquals(ForecastProvider.ECMWF_OPEN_DATA, sample.context.provider)
        assertEquals(ModelFamily.ECMWF_IFS, sample.context.modelFamily)
        assertEquals(VerificationParameter.TEMPERATURE, sample.parameter)
        assertEquals(1.0, sample.error.signed)
    }

    private fun context(
        modelRun: Instant = Instant.parse("2026-09-01T00:00:00Z"),
        validTime: Instant = Instant.parse("2026-09-01T06:00:00Z"),
        timeZoneId: String = "UTC",
    ): VerificationContext = VerificationContext(
        coordinate = ForecastCoordinate(59.9, 30.3),
        provider = ForecastProvider.ECMWF_OPEN_DATA,
        modelFamily = ModelFamily.ECMWF_IFS,
        modelRun = modelRun,
        validTime = validTime,
        timeZoneId = timeZoneId,
    )

    private fun abs(value: Double): Double = kotlin.math.abs(value)
}
