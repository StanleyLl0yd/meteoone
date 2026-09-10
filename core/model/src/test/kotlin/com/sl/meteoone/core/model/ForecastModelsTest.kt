package com.sl.meteoone.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ForecastModelsTest {
    private val time = Instant.parse("2026-09-10T12:00:00Z")
    private val origin = ForecastOrigin(
        provider = ForecastProvider.NOAA_NOMADS,
        modelFamily = ModelFamily.NOAA_GFS,
        modelRun = time.minusSeconds(6 * 60 * 60),
        generatedAt = time.minusSeconds(60),
    )
    private val location = ForecastLocation(
        latitude = 59.94,
        longitude = 30.31,
        elevationMeters = 10,
        timeZoneId = "Europe/Moscow",
    )

    @Test
    fun forecastOriginRequiresGenerationAtOrAfterKnownModelRun() {
        assertFailsWith<IllegalArgumentException> {
            ForecastOrigin(
                provider = ForecastProvider.NOAA_NOMADS,
                modelFamily = ModelFamily.NOAA_GFS,
                modelRun = time,
                generatedAt = time.minusSeconds(1),
            )
        }

        ForecastOrigin(
            provider = ForecastProvider.NOAA_NOMADS,
            modelFamily = ModelFamily.NOAA_GFS,
            modelRun = time,
            generatedAt = time,
        )
        ForecastOrigin(
            provider = ForecastProvider.NOAA_NOMADS,
            modelFamily = ModelFamily.NOAA_GFS,
            modelRun = time,
            generatedAt = time.plusSeconds(1),
        )
        ForecastOrigin(
            provider = ForecastProvider.OPEN_METEO,
            modelFamily = ModelFamily.NOAA_GFS,
            modelRun = null,
            generatedAt = time.minusSeconds(1),
        )
    }

    @Test
    fun forecastIntervalRequiresPositiveDuration() {
        val interval = ForecastInterval(
            start = time.minusSeconds(3600),
            end = time,
        )
        assertEquals(3600, interval.duration.seconds)

        assertFailsWith<IllegalArgumentException> {
            ForecastInterval(start = time, end = time)
        }
        assertFailsWith<IllegalArgumentException> {
            ForecastInterval(start = time.plusSeconds(1), end = time)
        }
    }

    @Test
    fun hourlyWeatherPointRejectsNonFiniteNumericValues() {
        val base = point()
        val invalidFactories = listOf<() -> HourlyWeatherPoint>(
            { base.copy(temperatureC = Double.NaN) },
            { base.copy(feelsLikeC = Double.POSITIVE_INFINITY) },
            { base.copy(dewPointC = Double.NEGATIVE_INFINITY) },
            { base.copy(humidityPercent = Double.NaN) },
            { base.copy(pressureSeaLevelHpa = Double.POSITIVE_INFINITY) },
            { base.copy(windSpeedMps = Double.NEGATIVE_INFINITY) },
            { base.copy(windGustMps = Double.NaN) },
            { base.copy(windDirectionDegrees = Double.POSITIVE_INFINITY) },
            { base.copy(precipitationMm = Double.NEGATIVE_INFINITY) },
            { base.copy(precipitationProbabilityPercent = Double.NaN) },
            { base.copy(cloudCoverPercent = Double.POSITIVE_INFINITY) },
            { base.copy(visibilityMeters = Double.NEGATIVE_INFINITY) },
        )

        invalidFactories.forEach { factory ->
            assertFailsWith<IllegalArgumentException> { factory() }
        }

        val finite = base.copy(
            temperatureC = -20.0,
            feelsLikeC = -25.0,
            dewPointC = -30.0,
            humidityPercent = 0.0,
            pressureSeaLevelHpa = 1000.0,
            windSpeedMps = 0.0,
            windGustMps = 0.0,
            windDirectionDegrees = 0.0,
            precipitationMm = 0.0,
            precipitationProbabilityPercent = 0.0,
            cloudCoverPercent = 0.0,
            visibilityMeters = 0.0,
        )
        assertEquals(0.0, finite.visibilityMeters)
    }

    @Test
    fun intervalMetadataRequiresMatchingValueAndPointEnd() {
        val oneHour = ForecastInterval(
            start = time.minusSeconds(3600),
            end = time,
        )
        point(
            precipitationMm = 1.0,
            precipitationInterval = oneHour,
            windGustMps = 9.0,
            windGustInterval = oneHour,
        )

        assertFailsWith<IllegalArgumentException> {
            point(precipitationMm = null, precipitationInterval = oneHour)
        }
        assertFailsWith<IllegalArgumentException> {
            point(windGustMps = null, windGustInterval = oneHour)
        }
        assertFailsWith<IllegalArgumentException> {
            point(
                precipitationMm = 1.0,
                precipitationInterval = ForecastInterval(
                    start = time.minusSeconds(7200),
                    end = time.minusSeconds(3600),
                ),
            )
        }
    }

    @Test
    fun sourceForecastRequiresNonEmptyStrictlyIncreasingTimeline() {
        val first = point(pointTime = time)
        val second = point(pointTime = time.plusSeconds(3600))
        val valid = SourceForecast(
            origin = origin,
            location = location,
            hourly = listOf(first, second),
        )
        assertEquals(listOf(first, second), valid.hourly)

        assertFailsWith<IllegalArgumentException> {
            SourceForecast(
                origin = origin,
                location = location,
                hourly = emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SourceForecast(
                origin = origin,
                location = location,
                hourly = listOf(first, first),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SourceForecast(
                origin = origin,
                location = location,
                hourly = listOf(second, first),
            )
        }
    }

    @Test
    fun fusedHourlyForecastRequiresPositiveCountsWithoutAssumingTheirOrdering() {
        val fused = FusedHourlyForecast(
            weather = point(),
            providerCount = 1,
            independentEvidenceCount = 1,
            agreement = ModelAgreement.INSUFFICIENT,
        )

        fused.copy(providerCount = 1, independentEvidenceCount = 2)
        fused.copy(providerCount = 2, independentEvidenceCount = 1)

        assertFailsWith<IllegalArgumentException> {
            fused.copy(providerCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            fused.copy(providerCount = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            fused.copy(independentEvidenceCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            fused.copy(independentEvidenceCount = -1)
        }
    }

    @Test
    fun fusedForecastRequiresNonEmptyStrictlyIncreasingTimeline() {
        val first = FusedHourlyForecast(
            weather = point(pointTime = time),
            providerCount = 1,
            independentEvidenceCount = 1,
            agreement = ModelAgreement.INSUFFICIENT,
        )
        val second = first.copy(weather = point(pointTime = time.plusSeconds(3600)))
        val valid = FusedForecast(
            location = location,
            generatedAt = time,
            hourly = listOf(first, second),
        )
        assertEquals(listOf(first, second), valid.hourly)

        assertFailsWith<IllegalArgumentException> {
            FusedForecast(
                location = location,
                generatedAt = time,
                hourly = emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FusedForecast(
                location = location,
                generatedAt = time,
                hourly = listOf(first, first),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FusedForecast(
                location = location,
                generatedAt = time,
                hourly = listOf(second, first),
            )
        }
    }

    private fun point(
        pointTime: Instant = time,
        precipitationMm: Double? = null,
        precipitationInterval: ForecastInterval? = null,
        windGustMps: Double? = null,
        windGustInterval: ForecastInterval? = null,
    ) = HourlyWeatherPoint(
        time = pointTime,
        temperatureC = null,
        feelsLikeC = null,
        dewPointC = null,
        humidityPercent = null,
        pressureSeaLevelHpa = null,
        windSpeedMps = null,
        windGustMps = windGustMps,
        windDirectionDegrees = null,
        precipitationMm = precipitationMm,
        precipitationProbabilityPercent = null,
        cloudCoverPercent = null,
        visibilityMeters = null,
        windGustInterval = windGustInterval,
        precipitationInterval = precipitationInterval,
    )
}
