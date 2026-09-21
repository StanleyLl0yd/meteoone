package com.sl.meteoone.core.database

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.verification.domain.ObservationStation
import com.sl.meteoone.verification.domain.ScalarVerificationSample
import com.sl.meteoone.verification.domain.SurfaceObservation
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VerificationSampleProducerTest {
    private val modelRun = Instant.parse("2026-09-20T06:00:00Z")
    private val validTime = Instant.parse("2026-09-20T12:00:00Z")
    private val station = ObservationStation(
        sourceId = "noaa-ncei-ghcnh",
        stationId = "RSM00026063",
        latitude = 59.9667,
        longitude = 30.3,
        elevationMeters = 4.0,
    )

    @Test
    fun retainedForecastAndObservationEvidenceProducesCanonicalSample() {
        val samples = DefaultVerificationSampleProducer().produce(
            forecast = storedRun(),
            observations = StoredVerificationObservationSeries(
                station = station,
                surfaceObservations = listOf(
                    SurfaceObservation(
                        station = station,
                        observedAt = validTime.minus(Duration.ofMinutes(10)),
                        temperatureC = 9.0,
                        pressureSeaLevelHpa = null,
                        windSpeedMps = null,
                        windDirectionDegrees = null,
                    ),
                ),
                precipitationObservations = emptyList(),
            ),
        )

        val sample = samples.single() as ScalarVerificationSample
        assertEquals(ForecastCoordinate(59.9, 30.3), sample.context.coordinate)
        assertEquals(ForecastProvider.OPEN_METEO, sample.context.provider)
        assertEquals(ModelFamily.ECMWF_IFS, sample.context.modelFamily)
        assertEquals(modelRun, sample.context.modelRun)
        assertEquals(validTime.minus(Duration.ofMinutes(10)), sample.observedAt)
        assertEquals(1.0, sample.error.signed)
    }

    @Test
    fun corruptedStoredLeadIsRejectedInsteadOfBeingReinferredSilently() {
        val corrupted = storedRun().copy(
            hourly = listOf(
                storedPoint().copy(leadTime = Duration.ofHours(5)),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            DefaultVerificationSampleProducer().produce(
                forecast = corrupted,
                observations = StoredVerificationObservationSeries(
                    station = station,
                    surfaceObservations = emptyList(),
                    precipitationObservations = emptyList(),
                ),
            )
        }
    }

    private fun storedRun(): StoredVerificationForecastRun = StoredVerificationForecastRun(
        coordinate = ForecastCoordinate(59.9, 30.3),
        provider = ForecastProvider.OPEN_METEO,
        modelFamily = ModelFamily.ECMWF_IFS,
        modelRun = modelRun,
        firstCapturedAt = modelRun.plusSeconds(60),
        elevationMeters = 4,
        timeZoneId = "Europe/Moscow",
        hourly = listOf(storedPoint()),
    )

    private fun storedPoint(): StoredVerificationForecastPoint = StoredVerificationForecastPoint(
        validTime = validTime,
        leadTime = Duration.ofHours(6),
        temperatureC = 10.0,
        pressureSeaLevelHpa = null,
        windSpeedMps = null,
        windDirectionDegrees = null,
        precipitationMm = null,
        precipitationInterval = null,
    )
}
