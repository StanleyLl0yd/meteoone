package com.sl.meteoone.forecast.domain

import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ForecastWeightedFusionTest {
    private val time = Instant.parse("2026-09-09T12:00:00Z")
    private val modelRun = Instant.parse("2026-09-09T06:00:00Z")
    private val generatedAt = Instant.parse("2026-09-09T11:59:00Z")
    private val location = ForecastLocation(
        latitude = 59.9,
        longitude = 30.3,
        elevationMeters = 10,
        timeZoneId = "Europe/Moscow",
    )

    @Test
    fun equalFallbackPreservesDirectionOnlyLegacyEvidence() {
        val sources = listOf(
            source(
                ForecastProvider.NOAA_NOMADS,
                ModelFamily.NOAA_GFS,
                temperature = 10.0,
                windSpeed = 10.0,
                windDirection = 0.0,
            ),
            source(
                ForecastProvider.DWD_OPEN_DATA,
                ModelFamily.DWD_ICON,
                temperature = 10.0,
                windSpeed = 10.0,
                windDirection = 90.0,
            ),
            source(
                ForecastProvider.ECMWF_OPEN_DATA,
                ModelFamily.ECMWF_IFS,
                temperature = 10.0,
                windSpeed = null,
                windDirection = 180.0,
            ),
        )

        val weather = ForecastFusionEngine().fuse(sources).hourly.single().weather

        assertEquals(10.0, weather.windSpeedMps)
        assertTrue(abs(assertNotNull(weather.windDirectionDegrees) - 90.0) < 1e-12)
    }

    @Test
    fun linkageFailureInWeightProviderFallsBackToLegacyFusion() {
        val sources = listOf(
            source(
                ForecastProvider.NOAA_NOMADS,
                ModelFamily.NOAA_GFS,
                temperature = 10.0,
            ),
            source(
                ForecastProvider.DWD_OPEN_DATA,
                ModelFamily.DWD_ICON,
                temperature = 20.0,
            ),
        )
        val baseline = ForecastFusionEngine().fuse(sources)
        val failing = ForecastFusionEngine(
            ForecastModelWeightProvider {
                throw UnsatisfiedLinkError("planned verification linkage failure")
            },
        ).fuse(sources)

        assertEquals(baseline, failing)
    }

    @Test
    fun measuredTemperatureUsesOnlyExactRunPathsInsideWeightedFamily() {
        val provider = RecordingWeightProvider(
            mapOf(
                ForecastWeightParameter.TEMPERATURE to mapOf(
                    ModelFamily.NOAA_GFS to 1.5,
                    ModelFamily.DWD_ICON to 1.0,
                ),
            ),
        )
        val result = ForecastFusionEngine(provider).fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 8.0,
                ),
                source(
                    ForecastProvider.OPEN_METEO,
                    ModelFamily.NOAA_GFS,
                    temperature = 12.0,
                    run = null,
                ),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    temperature = 20.0,
                ),
            ),
        )

        val hour = result.hourly.single()
        assertEquals(12.8, hour.weather.temperatureC)
        assertEquals(3, hour.providerCount)
        assertEquals(2, hour.independentEvidenceCount)
        val request = provider.requests.single {
            it.parameter == ForecastWeightParameter.TEMPERATURE
        }
        assertEquals(
            setOf(ModelFamily.NOAA_GFS, ModelFamily.DWD_ICON),
            request.modelFamilies,
        )
        assertEquals(modelRun, request.modelRun)
    }

    @Test
    fun familyWithoutExactRunRemainsNeutralAndOutsideMeasuredRequest() {
        val provider = RecordingWeightProvider(
            mapOf(
                ForecastWeightParameter.TEMPERATURE to mapOf(
                    ModelFamily.NOAA_GFS to 1.5,
                    ModelFamily.DWD_ICON to 1.0,
                ),
            ),
        )
        val result = ForecastFusionEngine(provider).fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                ),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    temperature = 20.0,
                ),
                source(
                    ForecastProvider.OPEN_METEO,
                    ModelFamily.ECMWF_IFS,
                    temperature = 30.0,
                    run = null,
                ),
            ),
        )

        val temperature = assertNotNull(result.hourly.single().weather.temperatureC)
        assertTrue(abs(temperature - (65.0 / 3.5)) < 1e-12)
        val request = provider.requests.single {
            it.parameter == ForecastWeightParameter.TEMPERATURE
        }
        assertEquals(
            setOf(ModelFamily.NOAA_GFS, ModelFamily.DWD_ICON),
            request.modelFamilies,
        )
    }

    @Test
    fun equalWeightDecisionPreservesLegacyFusionExactly() {
        val sources = listOf(
            source(
                ForecastProvider.NOAA_NOMADS,
                ModelFamily.NOAA_GFS,
                temperature = 8.0,
                feelsLike = 6.0,
                pressure = 1000.0,
                windSpeed = 5.0,
                windDirection = 350.0,
            ),
            source(
                ForecastProvider.DWD_OPEN_DATA,
                ModelFamily.DWD_ICON,
                temperature = 12.0,
                feelsLike = 10.0,
                pressure = 1004.0,
                windSpeed = 7.0,
                windDirection = 10.0,
            ),
        )
        val recording = RecordingWeightProvider(emptyMap())

        val baseline = ForecastFusionEngine().fuse(sources)
        val withProvider = ForecastFusionEngine(recording).fuse(sources)

        assertEquals(baseline, withProvider)
        assertTrue(recording.requests.isNotEmpty())
    }

    @Test
    fun fewerThanTwoExactFamiliesKeepsEqualWeights() {
        val provider = RecordingWeightProvider(
            mapOf(
                ForecastWeightParameter.TEMPERATURE to mapOf(
                    ModelFamily.NOAA_GFS to 1.5,
                    ModelFamily.DWD_ICON to 1.0,
                ),
            ),
        )
        val result = ForecastFusionEngine(provider).fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                ),
                source(
                    ForecastProvider.OPEN_METEO,
                    ModelFamily.DWD_ICON,
                    temperature = 20.0,
                    run = null,
                ),
            ),
        )

        assertEquals(15.0, result.hourly.single().weather.temperatureC)
        assertTrue(provider.requests.none {
            it.parameter == ForecastWeightParameter.TEMPERATURE
        })
    }

    @Test
    fun conflictingExactRunsInOneFamilyForceWholeParameterToEqualFallback() {
        val provider = RecordingWeightProvider(
            mapOf(
                ForecastWeightParameter.TEMPERATURE to mapOf(
                    ModelFamily.DWD_ICON to 1.5,
                    ModelFamily.ECMWF_IFS to 1.0,
                ),
            ),
        )
        val result = ForecastFusionEngine(provider).fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 8.0,
                    run = modelRun,
                ),
                source(
                    ForecastProvider.OPEN_METEO,
                    ModelFamily.NOAA_GFS,
                    temperature = 12.0,
                    run = modelRun.minusSeconds(6 * 3600),
                ),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    temperature = 20.0,
                ),
                source(
                    ForecastProvider.ECMWF_OPEN_DATA,
                    ModelFamily.ECMWF_IFS,
                    temperature = 30.0,
                ),
            ),
        )

        assertEquals(20.0, result.hourly.single().weather.temperatureC)
        assertTrue(provider.requests.none {
            it.parameter == ForecastWeightParameter.TEMPERATURE
        })
    }

    @Test
    fun unsupportedFieldsRemainEqualWhileTemperatureIsWeighted() {
        val provider = RecordingWeightProvider(
            mapOf(
                ForecastWeightParameter.TEMPERATURE to mapOf(
                    ModelFamily.NOAA_GFS to 1.5,
                    ModelFamily.DWD_ICON to 1.0,
                ),
            ),
        )
        val result = ForecastFusionEngine(provider).fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                    feelsLike = 10.0,
                ),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    temperature = 20.0,
                    feelsLike = 20.0,
                ),
            ),
        )

        val weather = result.hourly.single().weather
        assertEquals(14.0, weather.temperatureC)
        assertEquals(15.0, weather.feelsLikeC)
    }

    @Test
    fun precipitationIntervalSelectionPrecedesMeasuredWeighting() {
        val oneHour = ForecastInterval(time.minusSeconds(3600), time)
        val threeHours = ForecastInterval(time.minusSeconds(3 * 3600), time)
        val provider = RecordingWeightProvider(
            mapOf(
                ForecastWeightParameter.PRECIPITATION to mapOf(
                    ModelFamily.NOAA_GFS to 1.5,
                    ModelFamily.DWD_ICON to 1.0,
                ),
            ),
        )
        val result = ForecastFusionEngine(provider).fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                    precipitation = 2.0,
                    precipitationInterval = oneHour,
                ),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    temperature = 10.0,
                    precipitation = 4.0,
                    precipitationInterval = oneHour,
                ),
                source(
                    ForecastProvider.ECMWF_OPEN_DATA,
                    ModelFamily.ECMWF_IFS,
                    temperature = 10.0,
                    precipitation = 9.0,
                    precipitationInterval = threeHours,
                ),
            ),
        )

        val weather = result.hourly.single().weather
        assertTrue(abs(assertNotNull(weather.precipitationMm) - 2.8) < 1e-12)
        assertEquals(oneHour, weather.precipitationInterval)
        val request = provider.requests.single {
            it.parameter == ForecastWeightParameter.PRECIPITATION
        }
        assertEquals(
            setOf(ModelFamily.NOAA_GFS, ModelFamily.DWD_ICON),
            request.modelFamilies,
        )
    }

    @Test
    fun measuredWindWeightsUseVectorFusion() {
        val provider = RecordingWeightProvider(
            mapOf(
                ForecastWeightParameter.WIND to mapOf(
                    ModelFamily.NOAA_GFS to 1.5,
                    ModelFamily.DWD_ICON to 1.0,
                ),
            ),
        )
        val result = ForecastFusionEngine(provider).fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                    windSpeed = 10.0,
                    windDirection = 0.0,
                ),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    temperature = 10.0,
                    windSpeed = 10.0,
                    windDirection = 90.0,
                ),
            ),
        )

        val weather = result.hourly.single().weather
        assertTrue(abs(assertNotNull(weather.windSpeedMps) - 7.211102550927978) < 1e-12)
        assertTrue(
            abs(assertNotNull(weather.windDirectionDegrees) - 33.690067525979785) < 1e-12,
        )
    }

    @Test
    fun measuredWindDoesNotBorrowDirectionForAnUnusableExactProviderPath() {
        val provider = RecordingWeightProvider(
            mapOf(
                ForecastWeightParameter.WIND to mapOf(
                    ModelFamily.NOAA_GFS to 1.5,
                    ModelFamily.DWD_ICON to 1.0,
                ),
            ),
        )
        val result = ForecastFusionEngine(provider).fuse(
            listOf(
                source(
                    ForecastProvider.NOAA_NOMADS,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                    windSpeed = 10.0,
                    windDirection = 0.0,
                ),
                source(
                    ForecastProvider.OPEN_METEO,
                    ModelFamily.NOAA_GFS,
                    temperature = 10.0,
                    windSpeed = 100.0,
                    windDirection = null,
                ),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    temperature = 10.0,
                    windSpeed = 10.0,
                    windDirection = 90.0,
                ),
            ),
        )

        val weather = result.hourly.single().weather
        assertTrue(abs(assertNotNull(weather.windSpeedMps) - 7.211102550927978) < 1e-12)
        assertTrue(
            abs(assertNotNull(weather.windDirectionDegrees) - 33.690067525979785) < 1e-12,
        )
    }

    private fun source(
        provider: ForecastProvider,
        model: ModelFamily,
        temperature: Double,
        run: Instant? = modelRun,
        feelsLike: Double? = null,
        pressure: Double? = null,
        windSpeed: Double? = null,
        windDirection: Double? = null,
        precipitation: Double? = null,
        precipitationInterval: ForecastInterval? = null,
    ) = SourceForecast(
        origin = ForecastOrigin(
            provider = provider,
            modelFamily = model,
            modelRun = run,
            generatedAt = generatedAt,
        ),
        location = location,
        hourly = listOf(
            HourlyWeatherPoint(
                time = time,
                temperatureC = temperature,
                feelsLikeC = feelsLike,
                dewPointC = null,
                humidityPercent = null,
                pressureSeaLevelHpa = pressure,
                windSpeedMps = windSpeed,
                windGustMps = null,
                windDirectionDegrees = windDirection,
                precipitationMm = precipitation,
                precipitationProbabilityPercent = null,
                cloudCoverPercent = null,
                visibilityMeters = null,
                precipitationInterval = precipitationInterval,
            ),
        ),
    )

    private class RecordingWeightProvider(
        private val configured: Map<ForecastWeightParameter, Map<ModelFamily, Double>>,
    ) : ForecastModelWeightProvider {
        val requests = mutableListOf<ForecastModelWeightRequest>()

        override fun weights(request: ForecastModelWeightRequest): ForecastModelWeightDecision {
            requests += request
            val weights = configured[request.parameter]
                ?.takeIf { it.keys == request.modelFamilies }
                ?: return ForecastModelWeightDecision.EqualFallback
            return ForecastModelWeightDecision.Measured(weights)
        }
    }
}
