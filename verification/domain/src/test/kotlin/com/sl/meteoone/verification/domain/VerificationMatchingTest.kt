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
import kotlin.test.assertTrue

class VerificationMatchingTest {
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
    fun nearestSurfaceMatchUsesThirtyMinuteBoundaryAndEarlierTie() {
        val earlier = surface(validTime.minus(Duration.ofMinutes(30)), temperatureC = 10.0)
        val later = surface(validTime.plus(Duration.ofMinutes(30)), temperatureC = 20.0)

        val samples = VerificationSampleMatcher().match(
            forecast = run(
                provider = ForecastProvider.OPEN_METEO,
                point = point(validTime, temperatureC = 12.0),
            ),
            station = station,
            surfaceObservations = listOf(later, earlier),
            precipitationObservations = emptyList(),
        )

        val sample = samples.single() as ScalarVerificationSample
        assertEquals(earlier.observedAt, sample.observedAt)
        assertEquals(10.0, sample.observed)
        assertEquals(2.0, sample.error.signed)
        assertEquals(LeadTimeBucket.H0_6, sample.context.leadBucket)
    }

    @Test
    fun surfaceEvidenceOutsideToleranceOrMissingValuesCreatesNoSample() {
        val tooEarly = surface(
            validTime.minus(Duration.ofMinutes(30).plusNanos(1)),
            temperatureC = 10.0,
        )
        assertTrue(
            VerificationSampleMatcher().match(
                forecast = run(
                    provider = ForecastProvider.OPEN_METEO,
                    point = point(validTime, temperatureC = 12.0),
                ),
                station = station,
                surfaceObservations = listOf(tooEarly),
                precipitationObservations = emptyList(),
            ).isEmpty(),
        )

        assertTrue(
            VerificationSampleMatcher().match(
                forecast = run(
                    provider = ForecastProvider.OPEN_METEO,
                    point = point(validTime, temperatureC = 12.0),
                ),
                station = station,
                surfaceObservations = listOf(surface(validTime)),
                precipitationObservations = emptyList(),
            ).isEmpty(),
        )
    }

    @Test
    fun calmWindMatchesWithoutInventingDirectionButNonCalmMissingDirectionDoesNot() {
        val calm = VerificationSampleMatcher().match(
            forecast = run(
                provider = ForecastProvider.OPEN_METEO,
                point = point(
                    validTime,
                    windSpeedMps = 0.0,
                    windDirectionDegrees = null,
                ),
            ),
            station = station,
            surfaceObservations = listOf(
                surface(
                    validTime,
                    windSpeedMps = 0.0,
                    windDirectionDegrees = null,
                ),
            ),
            precipitationObservations = emptyList(),
        )
        val calmSample = calm.single() as WindVerificationSample
        assertEquals(0.0, calmSample.error.magnitudeMps)

        val unusable = VerificationSampleMatcher().match(
            forecast = run(
                provider = ForecastProvider.OPEN_METEO,
                point = point(
                    validTime,
                    windSpeedMps = 5.0,
                    windDirectionDegrees = 90.0,
                ),
            ),
            station = station,
            surfaceObservations = listOf(
                surface(
                    validTime,
                    windSpeedMps = 5.0,
                    windDirectionDegrees = null,
                ),
            ),
            precipitationObservations = emptyList(),
        )
        assertTrue(unusable.isEmpty())
    }

    @Test
    fun precipitationRequiresOneExactlyCompatibleInterval() {
        val exact = ForecastInterval(
            start = validTime.minus(Duration.ofHours(1)),
            end = validTime,
        )
        val incompatible = ForecastInterval(
            start = validTime.minus(Duration.ofHours(3)),
            end = validTime,
        )
        val forecast = run(
            provider = ForecastProvider.OPEN_METEO,
            point = point(
                validTime,
                precipitationMm = 1.5,
                precipitationInterval = exact,
            ),
        )

        val matched = VerificationSampleMatcher().match(
            forecast = forecast,
            station = station,
            surfaceObservations = emptyList(),
            precipitationObservations = listOf(
                PrecipitationObservation(station, incompatible, 9.0),
                PrecipitationObservation(station, exact, 1.0),
            ),
        )
        val sample = matched.single() as PrecipitationVerificationSample
        assertEquals(exact, sample.interval)
        assertEquals(0.5, sample.error.signed)

        val incompatibleOnly = VerificationSampleMatcher().match(
            forecast = forecast,
            station = station,
            surfaceObservations = emptyList(),
            precipitationObservations = listOf(
                PrecipitationObservation(station, incompatible, 9.0),
            ),
        )
        assertTrue(incompatibleOnly.isEmpty())
    }

    @Test
    fun duplicateProviderPathsRemainDistinguishableWithoutChangingModelFamily() {
        val observation = surface(validTime, temperatureC = 10.0)
        val matcher = VerificationSampleMatcher()
        val openMeteo = matcher.match(
            forecast = run(
                provider = ForecastProvider.OPEN_METEO,
                point = point(validTime, temperatureC = 12.0),
            ),
            station = station,
            surfaceObservations = listOf(observation),
            precipitationObservations = emptyList(),
        ).single()
        val direct = matcher.match(
            forecast = run(
                provider = ForecastProvider.ECMWF_OPEN_DATA,
                point = point(validTime, temperatureC = 12.0),
            ),
            station = station,
            surfaceObservations = listOf(observation),
            precipitationObservations = emptyList(),
        ).single()

        assertEquals(ModelFamily.ECMWF_IFS, openMeteo.context.modelFamily)
        assertEquals(ModelFamily.ECMWF_IFS, direct.context.modelFamily)
        assertEquals(ForecastProvider.OPEN_METEO, openMeteo.context.provider)
        assertEquals(ForecastProvider.ECMWF_OPEN_DATA, direct.context.provider)
    }

    @Test
    fun matcherRejectsBroaderToleranceAndAmbiguousObservationIdentity() {
        assertFailsWith<IllegalArgumentException> {
            VerificationSampleMatcher(Duration.ofMinutes(30).plusNanos(1))
        }

        val duplicateTime = validTime.minus(Duration.ofMinutes(5))
        assertFailsWith<IllegalArgumentException> {
            VerificationSampleMatcher().match(
                forecast = run(
                    provider = ForecastProvider.OPEN_METEO,
                    point = point(validTime, temperatureC = 12.0),
                ),
                station = station,
                surfaceObservations = listOf(
                    surface(duplicateTime, temperatureC = 10.0),
                    surface(duplicateTime, temperatureC = 11.0),
                ),
                precipitationObservations = emptyList(),
            )
        }
    }

    private fun run(
        provider: ForecastProvider,
        point: ForecastVerificationPointEvidence,
    ): ForecastVerificationRunEvidence = ForecastVerificationRunEvidence(
        coordinate = ForecastCoordinate(59.9, 30.3),
        provider = provider,
        modelFamily = ModelFamily.ECMWF_IFS,
        modelRun = modelRun,
        timeZoneId = "Europe/Moscow",
        hourly = listOf(point),
    )

    private fun point(
        time: Instant,
        temperatureC: Double? = null,
        pressureSeaLevelHpa: Double? = null,
        windSpeedMps: Double? = null,
        windDirectionDegrees: Double? = null,
        precipitationMm: Double? = null,
        precipitationInterval: ForecastInterval? = null,
    ): ForecastVerificationPointEvidence = ForecastVerificationPointEvidence(
        validTime = time,
        temperatureC = temperatureC,
        pressureSeaLevelHpa = pressureSeaLevelHpa,
        windSpeedMps = windSpeedMps,
        windDirectionDegrees = windDirectionDegrees,
        precipitationMm = precipitationMm,
        precipitationInterval = precipitationInterval,
    )

    private fun surface(
        time: Instant,
        temperatureC: Double? = null,
        pressureSeaLevelHpa: Double? = null,
        windSpeedMps: Double? = null,
        windDirectionDegrees: Double? = null,
    ): SurfaceObservation = SurfaceObservation(
        station = station,
        observedAt = time,
        temperatureC = temperatureC,
        pressureSeaLevelHpa = pressureSeaLevelHpa,
        windSpeedMps = windSpeedMps,
        windDirectionDegrees = windDirectionDegrees,
    )
}
