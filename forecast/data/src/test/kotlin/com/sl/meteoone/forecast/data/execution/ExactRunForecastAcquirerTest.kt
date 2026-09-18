package com.sl.meteoone.forecast.data.execution

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.model.WeatherCondition
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactRunForecastAcquirerTest {
    private val coordinate = ForecastCoordinate(59.9, 30.3)
    private val capturedAt = Instant.parse("2026-09-18T12:00:00Z")
    private val firstRun = Instant.parse("2026-09-15T00:00:00Z")
    private val secondRun = Instant.parse("2026-09-16T00:00:00Z")

    @Test
    fun acquiresThreeIndependentModelFamiliesForEachBoundedRun() {
        val source = RecordingSource()
        val result = ExactRunForecastAcquirer(source).acquire(
            coordinate = coordinate,
            elevationMeters = 12,
            timeZoneId = "Europe/Moscow",
            modelRuns = listOf(firstRun, secondRun),
            capturedAt = capturedAt,
        )

        assertEquals(6, source.calls.size)
        assertEquals(6, result.forecasts.size)
        assertEquals(emptyList(), result.failedSources)
        assertEquals(
            listOf(
                ModelFamily.ECMWF_IFS,
                ModelFamily.DWD_ICON,
                ModelFamily.NOAA_GFS,
                ModelFamily.ECMWF_IFS,
                ModelFamily.DWD_ICON,
                ModelFamily.NOAA_GFS,
            ),
            result.forecasts.map { it.origin.modelFamily },
        )
        assertEquals(
            listOf(firstRun, firstRun, firstRun, secondRun, secondRun, secondRun),
            result.forecasts.map { it.origin.modelRun },
        )
        assertTrue(
            result.forecasts.all {
                it.origin.provider == ForecastProvider.OPEN_METEO &&
                    it.location.latitude == coordinate.latitude &&
                    it.location.longitude == coordinate.longitude
            },
        )
    }

    @Test
    fun oneModelFailureDoesNotEraseOtherExactRunEvidence() {
        val failed = FailureKey(
            model = OpenMeteoModel.DWD_ICON_GLOBAL,
            run = firstRun,
        )
        val source = RecordingSource(failures = setOf(failed))

        val result = ExactRunForecastAcquirer(source).acquire(
            coordinate = coordinate,
            elevationMeters = null,
            timeZoneId = "UTC",
            modelRuns = listOf(firstRun),
            capturedAt = capturedAt,
        )

        assertEquals(3, source.calls.size)
        assertEquals(2, result.forecasts.size)
        assertEquals(
            listOf(
                ExactRunSourceIdentity(
                    provider = ForecastProvider.OPEN_METEO,
                    modelFamily = ModelFamily.DWD_ICON,
                    modelRun = firstRun,
                ),
            ),
            result.failedSources,
        )
    }

    @Test
    fun acquisitionRejectsMoreThanTwoRunsBeforeNetworkWork() {
        val source = RecordingSource()

        assertFailsWith<IllegalArgumentException> {
            ExactRunForecastAcquirer(source).acquire(
                coordinate = coordinate,
                elevationMeters = null,
                timeZoneId = "UTC",
                modelRuns = listOf(
                    Instant.parse("2026-09-14T00:00:00Z"),
                    firstRun,
                    secondRun,
                ),
                capturedAt = capturedAt,
            )
        }
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun acquisitionRequiresChronologicalUniqueCommon00zRuns() {
        val source = RecordingSource()
        val acquirer = ExactRunForecastAcquirer(source)

        assertFailsWith<IllegalArgumentException> {
            acquirer.acquire(
                coordinate,
                null,
                "UTC",
                listOf(secondRun, firstRun),
                capturedAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            acquirer.acquire(
                coordinate,
                null,
                "UTC",
                listOf(firstRun, firstRun),
                capturedAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            acquirer.acquire(
                coordinate,
                null,
                "UTC",
                listOf(Instant.parse("2026-09-15T06:00:00Z")),
                capturedAt,
            )
        }
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun acquisitionDoesNotRequestRunBeforePublicationGuard() {
        val source = RecordingSource()
        val run = Instant.parse("2026-09-18T00:00:00Z")

        assertFailsWith<IllegalArgumentException> {
            ExactRunForecastAcquirer(source).acquire(
                coordinate = coordinate,
                elevationMeters = null,
                timeZoneId = "UTC",
                modelRuns = listOf(run),
                capturedAt = Instant.parse("2026-09-18T06:59:59Z"),
            )
        }
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun provenanceMismatchBecomesIsolatedFailure() {
        val source = RecordingSource(
            mutate = { forecast ->
                forecast.copy(
                    origin = forecast.origin.copy(
                        modelFamily = ModelFamily.NOAA_GFS,
                    ),
                )
            },
        )

        val result = ExactRunForecastAcquirer(source).acquire(
            coordinate = coordinate,
            elevationMeters = null,
            timeZoneId = "UTC",
            modelRuns = listOf(firstRun),
            capturedAt = capturedAt,
        )

        assertEquals(1, result.forecasts.size)
        assertEquals(2, result.failedSources.size)
        assertTrue(
            ExactRunSourceIdentity(
                ForecastProvider.OPEN_METEO,
                ModelFamily.ECMWF_IFS,
                firstRun,
            ) in result.failedSources,
        )
        assertTrue(
            ExactRunSourceIdentity(
                ForecastProvider.OPEN_METEO,
                ModelFamily.DWD_ICON,
                firstRun,
            ) in result.failedSources,
        )
    }

    private data class FailureKey(
        val model: OpenMeteoModel,
        val run: Instant,
    )

    private class RecordingSource(
        private val failures: Set<FailureKey> = emptySet(),
        private val mutate: (SourceForecast) -> SourceForecast = { it },
    ) : ExactRunSource {
        val calls = mutableListOf<FailureKey>()

        override fun fetch(
            model: OpenMeteoModel,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            modelRun: Instant,
            capturedAt: Instant,
        ): SourceForecast {
            val key = FailureKey(model, modelRun)
            calls += key
            if (key in failures) {
                throw IllegalStateException("scripted exact-run failure")
            }
            return mutate(
                SourceForecast(
                    origin = ForecastOrigin(
                        provider = ForecastProvider.OPEN_METEO,
                        modelFamily = model.modelFamily,
                        modelRun = modelRun,
                        generatedAt = capturedAt,
                    ),
                    location = location,
                    hourly = listOf(
                        HourlyWeatherPoint(
                            time = modelRun.plusSeconds(3600),
                            temperatureC = 10.0,
                            feelsLikeC = null,
                            dewPointC = null,
                            humidityPercent = null,
                            pressureSeaLevelHpa = 1012.0,
                            windSpeedMps = 4.0,
                            windGustMps = null,
                            windDirectionDegrees = 270.0,
                            precipitationMm = 0.0,
                            precipitationProbabilityPercent = null,
                            cloudCoverPercent = null,
                            visibilityMeters = null,
                            condition = WeatherCondition.UNKNOWN,
                        ),
                    ),
                ),
            )
        }
    }
}
