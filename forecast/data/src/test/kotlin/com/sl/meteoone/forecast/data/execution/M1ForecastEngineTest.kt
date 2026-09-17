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
import com.sl.meteoone.forecast.domain.ForecastSourceIdentity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class M1ForecastEngineTest {
    private val location = ForecastLocation(
        latitude = 59.9,
        longitude = 30.3,
        elevationMeters = 20,
        timeZoneId = "Europe/Moscow",
    )
    private val generatedAt = Instant.parse("2026-09-14T12:30:00Z")

    @Test
    fun composesExact72HourBaselineWithBoundedDirectCrossChecks() {
        val executor = FakeForecastSourceExecutor()
        val available = assertIs<M1ForecastEngineResult.Available>(
            M1ForecastEngine(executor).forecast(location, generatedAt),
        )

        assertEquals(72, available.forecast.hourly.size)
        assertEquals(
            Instant.parse("2026-09-14T13:00:00Z"),
            available.forecast.hourly.first().weather.time,
        )
        assertEquals(
            Instant.parse("2026-09-17T12:00:00Z"),
            available.forecast.hourly.last().weather.time,
        )

        val ecmwfCrossCheckTime = Instant.parse("2026-09-14T15:00:00Z")
        val ecmwfCrossCheckPoint = available.forecast.hourly.single {
            it.weather.time == ecmwfCrossCheckTime
        }
        assertEquals(2, ecmwfCrossCheckPoint.providerCount)
        assertEquals(3, ecmwfCrossCheckPoint.independentEvidenceCount)
        assertEquals(6, available.successfulSources.size)
        assertEquals(
            available.successfulSources,
            available.sourceForecasts.map { source ->
                m1Identity(source.origin.provider, source.origin.modelFamily)
            },
        )
        assertEquals(
            listOf(1, 72, 1, 72, 1, 72),
            available.sourceForecasts.map { it.hourly.size },
        )

        assertEquals(Instant.parse("2026-09-14T00:00:00Z"), executor.modelRuns.single())
        assertEquals(listOf(13), executor.noaaHours)
        assertEquals(listOf(15), executor.ecmwfHours)
        assertEquals(listOf(13), executor.dwdHours)
        assertEquals(
            listOf(
                OpenMeteoModel.NOAA_GFS_GLOBAL,
                OpenMeteoModel.ECMWF_IFS,
                OpenMeteoModel.DWD_ICON_GLOBAL,
            ),
            executor.openMeteoModels,
        )
        assertEquals(emptyList(), available.failedSources)
    }

    @Test
    fun preservesPartialFailureWhileKeeping72HourForecast() {
        val executor = FakeForecastSourceExecutor(
            failures = setOf(
                ForecastSourceIdentity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS),
                ForecastSourceIdentity(ForecastProvider.OPEN_METEO, ModelFamily.DWD_ICON),
            ),
        )

        val available = assertIs<M1ForecastEngineResult.Available>(
            M1ForecastEngine(executor).forecast(location, generatedAt),
        )

        assertEquals(72, available.forecast.hourly.size)
        assertEquals(4, available.successfulSources.size)
        assertEquals(
            available.successfulSources,
            available.sourceForecasts.map { source ->
                m1Identity(source.origin.provider, source.origin.modelFamily)
            },
        )
        assertEquals(2, available.failedSources.size)
        assertTrue(
            m1Identity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS) in
                available.failedSources,
        )
        assertTrue(
            m1Identity(ForecastProvider.OPEN_METEO, ModelFamily.DWD_ICON) in
                available.failedSources,
        )
    }

    @Test
    fun reportsUnavailableWhenEvery72HourBaselineFailsEvenIfDirectCrossChecksSucceeded() {
        val executor = FakeForecastSourceExecutor(
            failures = setOf(
                ForecastSourceIdentity(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS),
                ForecastSourceIdentity(ForecastProvider.OPEN_METEO, ModelFamily.ECMWF_IFS),
                ForecastSourceIdentity(ForecastProvider.OPEN_METEO, ModelFamily.DWD_ICON),
            ),
        )

        val unavailable = assertIs<M1ForecastEngineResult.Unavailable>(
            M1ForecastEngine(executor).forecast(location, generatedAt),
        )

        assertEquals(
            setOf(
                m1Identity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS),
                m1Identity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS),
                m1Identity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON),
            ),
            unavailable.successfulCrossChecks.toSet(),
        )
        assertEquals(3, unavailable.failedSources.size)
    }

    @Test
    fun reportsUnavailableWhenEverySourceFails() {
        val executor = FakeForecastSourceExecutor(
            failures = setOf(
                ForecastSourceIdentity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS),
                ForecastSourceIdentity(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS),
                ForecastSourceIdentity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS),
                ForecastSourceIdentity(ForecastProvider.OPEN_METEO, ModelFamily.ECMWF_IFS),
                ForecastSourceIdentity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON),
                ForecastSourceIdentity(ForecastProvider.OPEN_METEO, ModelFamily.DWD_ICON),
            ),
        )

        val unavailable = assertIs<M1ForecastEngineResult.Unavailable>(
            M1ForecastEngine(executor).forecast(location, generatedAt),
        )

        assertEquals(emptyList(), unavailable.successfulCrossChecks)
        assertEquals(6, unavailable.failedSources.size)
    }

    @Test
    fun rejectsLocationThatDidNotPassForecastCoordinatePrivacyNormalization() {
        val rawLocation = location.copy(latitude = 59.9343, longitude = 30.3351)

        assertFailsWith<IllegalArgumentException> {
            M1ForecastEngine(FakeForecastSourceExecutor()).forecast(rawLocation, generatedAt)
        }
    }

    @Test
    fun selectsConservativeOperationalRunAndAlignsEcmwfToThreeHours() {
        val beforeNextEligibleCycle = Instant.parse("2026-09-14T12:59:59Z")
        val afterNextEligibleCycle = Instant.parse("2026-09-14T13:00:01Z")

        assertEquals(
            Instant.parse("2026-09-14T00:00:00Z"),
            M1OfficialRunPolicy.selectModelRun(beforeNextEligibleCycle),
        )
        assertEquals(
            Instant.parse("2026-09-14T06:00:00Z"),
            M1OfficialRunPolicy.selectModelRun(afterNextEligibleCycle),
        )
        assertEquals(
            13,
            M1OfficialRunPolicy.hourlyForecastHour(
                Instant.parse("2026-09-14T00:00:00Z"),
                generatedAt,
            ),
        )
        assertEquals(
            15,
            M1OfficialRunPolicy.ecmwfForecastHour(
                Instant.parse("2026-09-14T00:00:00Z"),
                generatedAt,
            ),
        )
    }

    private fun m1Identity(
        provider: ForecastProvider,
        modelFamily: ModelFamily,
    ) = M1ForecastSourceIdentity(provider, modelFamily)

    private inner class FakeForecastSourceExecutor(
        private val failures: Set<ForecastSourceIdentity> = emptySet(),
    ) : ForecastSourceExecutor {
        val modelRuns = linkedSetOf<Instant>()
        val noaaHours = mutableListOf<Int>()
        val ecmwfHours = mutableListOf<Int>()
        val dwdHours = mutableListOf<Int>()
        val openMeteoModels = mutableListOf<OpenMeteoModel>()

        override fun openMeteo(
            model: OpenMeteoModel,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): SourceForecast {
            openMeteoModels += model
            val identity = ForecastSourceIdentity(ForecastProvider.OPEN_METEO, model.modelFamily)
            failIfRequested(identity)
            return forecast(
                provider = identity.provider,
                modelFamily = identity.modelFamily,
                modelRun = null,
                firstTime = Instant.parse("2026-09-14T13:00:00Z"),
                hours = 72,
            )
        }

        override fun noaa(
            modelRun: Instant,
            forecastHour: Int,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): SourceForecast {
            modelRuns += modelRun
            noaaHours += forecastHour
            val identity = ForecastSourceIdentity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS)
            failIfRequested(identity)
            return forecast(
                provider = identity.provider,
                modelFamily = identity.modelFamily,
                modelRun = modelRun,
                firstTime = modelRun.plusSeconds(forecastHour * 3600L),
                hours = 1,
            )
        }

        override fun ecmwf(
            modelRun: Instant,
            forecastHour: Int,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): SourceForecast {
            modelRuns += modelRun
            ecmwfHours += forecastHour
            val identity = ForecastSourceIdentity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS)
            failIfRequested(identity)
            return forecast(
                provider = identity.provider,
                modelFamily = identity.modelFamily,
                modelRun = modelRun,
                firstTime = modelRun.plusSeconds(forecastHour * 3600L),
                hours = 1,
            )
        }

        override fun dwd(
            modelRun: Instant,
            forecastHour: Int,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): SourceForecast {
            modelRuns += modelRun
            dwdHours += forecastHour
            val identity = ForecastSourceIdentity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON)
            failIfRequested(identity)
            return forecast(
                provider = identity.provider,
                modelFamily = identity.modelFamily,
                modelRun = modelRun,
                firstTime = modelRun.plusSeconds(forecastHour * 3600L),
                hours = 1,
            )
        }

        private fun failIfRequested(identity: ForecastSourceIdentity) {
            if (identity in failures) throw IllegalStateException("planned test failure")
        }

        private fun forecast(
            provider: ForecastProvider,
            modelFamily: ModelFamily,
            modelRun: Instant?,
            firstTime: Instant,
            hours: Int,
        ): SourceForecast = SourceForecast(
            origin = ForecastOrigin(
                provider = provider,
                modelFamily = modelFamily,
                modelRun = modelRun,
                generatedAt = generatedAt,
            ),
            location = location,
            hourly = List(hours) { index ->
                HourlyWeatherPoint(
                    time = firstTime.plusSeconds(index * 3600L),
                    temperatureC = 10.0 + modelFamily.ordinal,
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
                    condition = WeatherCondition.UNKNOWN,
                )
            },
        )
    }
}
