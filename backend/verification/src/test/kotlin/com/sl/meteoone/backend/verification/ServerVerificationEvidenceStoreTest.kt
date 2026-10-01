package com.sl.meteoone.backend.verification

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.verification.domain.ObservationStation
import com.sl.meteoone.verification.domain.PrecipitationObservation
import com.sl.meteoone.verification.domain.SurfaceObservation
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerVerificationEvidenceStoreTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val coordinate = ForecastCoordinate(59.9, 30.3)
    private val location = ForecastLocation(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        elevationMeters = 12,
        timeZoneId = "Europe/Moscow",
    )
    private val station = ObservationStation(
        sourceId = "noaa-ncei-ghcnh",
        stationId = "RSM00026063",
        latitude = 59.9667,
        longitude = 30.3,
        elevationMeters = 4.0,
    )

    @Test
    fun forecastHistoryIsImmutableDeduplicatedAndRetentionBounded() = runBlocking {
        val store = store()
        val run = now.minus(Duration.ofDays(1))

        val first = store.archiveForecasts(
            coordinate,
            listOf(forecast(ModelFamily.NOAA_GFS, run, temperature = 10.0)),
        )
        val duplicate = store.archiveForecasts(
            coordinate,
            listOf(forecast(ModelFamily.NOAA_GFS, run, temperature = 99.0)),
        )
        val expired = store.archiveForecasts(
            coordinate,
            listOf(forecast(ModelFamily.DWD_ICON, now.minus(Duration.ofDays(181)))),
        )
        val withoutRun = store.archiveForecasts(
            coordinate,
            listOf(forecastWithoutRun()),
        )

        assertEquals(1, first.insertedRuns)
        assertEquals(1, duplicate.existingRuns)
        assertEquals(1, expired.skippedExpiredRuns)
        assertEquals(1, withoutRun.skippedWithoutModelRun)

        val stored = store.forecastsSince(
            coordinate,
            now.minus(Duration.ofDays(30)),
        )
        assertEquals(1, stored.size)
        assertEquals(10.0, stored.single().evidence.hourly.single().temperatureC)
    }

    @Test
    fun forecastCardinalityKeepsOnlyNewestRunsAndCoordinates() = runBlocking {
        val store = store(
            maxCoordinates = 1,
            maxRunsPerCoordinate = 2,
        )
        val runs = listOf(3L, 2L, 1L).map { days ->
            now.minus(Duration.ofDays(days))
        }
        store.archiveForecasts(
            coordinate,
            runs.mapIndexed { index, run ->
                forecast(
                    family = ModelFamily.NOAA_GFS,
                    run = run,
                    temperature = index.toDouble(),
                )
            },
        )

        assertEquals(
            runs.takeLast(2),
            store.forecastsSince(coordinate, now.minus(Duration.ofDays(30)))
                .map { it.evidence.modelRun },
        )

        val other = ForecastCoordinate(60.0, 30.3)
        val otherLocation = ForecastLocation(
            latitude = other.latitude,
            longitude = other.longitude,
            elevationMeters = 12,
            timeZoneId = "Europe/Moscow",
        )
        store.archiveForecasts(
            other,
            listOf(
                forecast(
                    family = ModelFamily.ECMWF_IFS,
                    run = now.minus(Duration.ofHours(12)),
                    location = otherLocation,
                ),
            ),
        )

        assertEquals(1, store.trackedCoordinateCount())
        assertEquals(emptyList(), store.forecastsSince(coordinate, now.minus(Duration.ofDays(30))))
    }

    @Test
    fun observationArchiveEnrichesButNeverOverwritesExistingEvidence() = runBlocking {
        val store = store()
        val observedAt = now.minus(Duration.ofHours(2))
        val interval = ForecastInterval(
            start = observedAt.minus(Duration.ofHours(1)),
            end = observedAt,
        )

        store.archiveObservations(
            station,
            surfaceObservations = listOf(
                surface(observedAt, temperature = 5.0),
            ),
            precipitationObservations = listOf(
                PrecipitationObservation(station, interval, 1.0),
            ),
        )
        val second = store.archiveObservations(
            station,
            surfaceObservations = listOf(
                surface(
                    observedAt = observedAt,
                    temperature = 99.0,
                    pressure = 1013.0,
                ),
            ),
            precipitationObservations = listOf(
                PrecipitationObservation(station, interval, 99.0),
            ),
        )

        assertEquals(1, second.enrichedSurface)
        assertEquals(1, second.existingPrecipitation)

        val series = requireNotNull(
            store.observationsSince(
                station.sourceId,
                station.stationId,
                now.minus(Duration.ofDays(1)),
            ),
        )
        val surface = series.surfaceObservations.single()
        assertEquals(5.0, surface.temperatureC)
        assertEquals(1013.0, surface.pressureSeaLevelHpa)
        assertEquals(1.0, series.precipitationObservations.single().amountMm)
    }

    @Test
    fun observationRetentionAndStationCardinalityAreBounded() = runBlocking {
        val store = store(maxStations = 1)
        val expiredAt = now.minus(Duration.ofDays(181))
        val expired = store.archiveObservations(
            station,
            surfaceObservations = listOf(surface(expiredAt, temperature = 5.0)),
            precipitationObservations = emptyList(),
        )
        assertEquals(1, expired.skippedExpiredSurface)

        store.archiveObservations(
            station,
            surfaceObservations = listOf(surface(now.minus(Duration.ofHours(1)), temperature = 5.0)),
            precipitationObservations = emptyList(),
        )

        val other = ObservationStation(
            sourceId = station.sourceId,
            stationId = "RSM00027612",
            latitude = 60.0,
            longitude = 30.4,
            elevationMeters = 8.0,
        )
        store.archiveObservations(
            other,
            surfaceObservations = listOf(
                SurfaceObservation(
                    station = other,
                    observedAt = now.minus(Duration.ofHours(1)),
                    temperatureC = 4.0,
                    pressureSeaLevelHpa = null,
                    windSpeedMps = null,
                    windDirectionDegrees = null,
                ),
            ),
            precipitationObservations = emptyList(),
        )

        assertEquals(1, store.trackedStationCount())
        assertEquals(listOf(other), store.stations(station.sourceId))
        assertNull(
            store.observationsSince(
                station.sourceId,
                station.stationId,
                now.minus(Duration.ofDays(1)),
            ),
        )
    }

    @Test
    fun futureOrPrivacyMismatchedEvidenceFailsClosed() = runBlocking {
        val store = store()
        assertFailsWith<IllegalArgumentException> {
            store.archiveObservations(
                station,
                surfaceObservations = listOf(
                    surface(now.plusSeconds(1), temperature = 5.0),
                ),
                precipitationObservations = emptyList(),
            )
        }

        val wrongLocation = ForecastLocation(
            latitude = 60.0,
            longitude = 30.3,
            elevationMeters = 12,
            timeZoneId = "Europe/Moscow",
        )
        assertFailsWith<IllegalArgumentException> {
            store.archiveForecasts(
                coordinate,
                listOf(
                    forecast(
                        ModelFamily.NOAA_GFS,
                        now.minus(Duration.ofHours(12)),
                        location = wrongLocation,
                    ),
                ),
            )
        }
    }

    private fun store(
        maxCoordinates: Int = 8,
        maxRunsPerCoordinate: Int = 32,
        maxStations: Int = 8,
    ): InMemoryServerVerificationEvidenceStore =
        InMemoryServerVerificationEvidenceStore(
            clock = Clock.fixed(now, ZoneOffset.UTC),
            retention = Duration.ofDays(180),
            maxCoordinates = maxCoordinates,
            maxRunsPerCoordinate = maxRunsPerCoordinate,
            maxStations = maxStations,
            maxSurfacePerStation = 32,
            maxPrecipitationPerStation = 32,
        )

    private fun forecast(
        family: ModelFamily,
        run: Instant,
        temperature: Double = 10.0,
        location: ForecastLocation = this.location,
    ): SourceForecast {
        val provider = when (family) {
            ModelFamily.NOAA_GFS -> ForecastProvider.OPEN_METEO
            ModelFamily.ECMWF_IFS -> ForecastProvider.OPEN_METEO
            ModelFamily.DWD_ICON -> ForecastProvider.OPEN_METEO
            ModelFamily.UNKNOWN -> error("Unknown model family is not test evidence")
        }
        return SourceForecast(
            origin = ForecastOrigin(
                provider = provider,
                modelFamily = family,
                modelRun = run,
                generatedAt = run.plus(Duration.ofHours(8)),
            ),
            location = location,
            hourly = listOf(
                HourlyWeatherPoint(
                    time = run.plus(Duration.ofHours(6)),
                    temperatureC = temperature,
                    feelsLikeC = null,
                    dewPointC = null,
                    humidityPercent = null,
                    pressureSeaLevelHpa = null,
                    windSpeedMps = null,
                    windGustMps = null,
                    windDirectionDegrees = null,
                    precipitationMm = null,
                    precipitationProbabilityPercent = null,
                    cloudCoverPercent = null,
                    visibilityMeters = null,
                ),
            ),
        )
    }

    private fun forecastWithoutRun(): SourceForecast =
        SourceForecast(
            origin = ForecastOrigin(
                provider = ForecastProvider.OPEN_METEO,
                modelFamily = ModelFamily.NOAA_GFS,
                modelRun = null,
                generatedAt = now.minus(Duration.ofHours(1)),
            ),
            location = location,
            hourly = listOf(
                HourlyWeatherPoint(
                    time = now,
                    temperatureC = 10.0,
                    feelsLikeC = null,
                    dewPointC = null,
                    humidityPercent = null,
                    pressureSeaLevelHpa = null,
                    windSpeedMps = null,
                    windGustMps = null,
                    windDirectionDegrees = null,
                    precipitationMm = null,
                    precipitationProbabilityPercent = null,
                    cloudCoverPercent = null,
                    visibilityMeters = null,
                ),
            ),
        )

    private fun surface(
        observedAt: Instant,
        temperature: Double?,
        pressure: Double? = null,
    ): SurfaceObservation =
        SurfaceObservation(
            station = station,
            observedAt = observedAt,
            temperatureC = temperature,
            pressureSeaLevelHpa = pressure,
            windSpeedMps = null,
            windDirectionDegrees = null,
        )
}
