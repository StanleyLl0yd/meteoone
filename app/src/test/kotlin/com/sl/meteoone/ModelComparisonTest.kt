package com.sl.meteoone

import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.forecast.repository.ForecastCacheState
import com.sl.meteoone.forecast.repository.ForecastFreshness
import com.sl.meteoone.forecast.repository.ForecastSourceIdentity
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelComparisonTest {
    private val generatedAt = Instant.parse("2026-09-18T06:00:00Z")
    private val hour = generatedAt.plus(Duration.ofHours(1))
    private val location = ForecastLocation(59.9, 30.3, null, "Europe/Moscow")

    @Test
    fun groupsProviderPathsByModelFamilyWithoutCountingThemAsIndependentModels() {
        val cache = cache(
            sources = listOf(
                source(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS, hour, temperature = 8.0),
                source(ForecastProvider.OPEN_METEO, ModelFamily.ECMWF_IFS, hour, temperature = 8.5),
            ),
        )

        val comparison = buildModelComparisonData(cache)

        assertFalse(comparison.isAvailable(ModelComparisonParameter.TEMPERATURE))
        val ecmwf = comparison.families.first()
        assertEquals(ModelFamily.ECMWF_IFS, ecmwf.modelFamily)
        assertEquals(
            listOf(ForecastProvider.ECMWF_OPEN_DATA, ForecastProvider.OPEN_METEO),
            ecmwf.providerPaths.map { it.provider },
        )
    }

    @Test
    fun enablesTemperatureWhenTwoModelFamiliesHaveExactHourValues() {
        val cache = cache(
            sources = listOf(
                source(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS, hour, temperature = 8.0),
                source(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON, hour, temperature = 9.0),
            ),
        )

        val comparison = buildModelComparisonData(cache)

        assertTrue(comparison.isAvailable(ModelComparisonParameter.TEMPERATURE))
        assertEquals(listOf(hour), comparison.hoursFor(ModelComparisonParameter.TEMPERATURE))
    }

    @Test
    fun neverInterpolatesMissingExactHourValues() {
        val cache = cache(
            sources = listOf(
                source(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS, hour, temperature = 8.0),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    hour.plus(Duration.ofHours(1)),
                    temperature = 9.0,
                ),
            ),
        )

        val comparison = buildModelComparisonData(cache)

        assertFalse(comparison.isAvailable(ModelComparisonParameter.TEMPERATURE))
    }

    @Test
    fun precipitationRequiresTheSameAccumulationIntervalAsFusion() {
        val fusedInterval = ForecastInterval(hour.minus(Duration.ofHours(1)), hour)
        val otherInterval = ForecastInterval(hour.minus(Duration.ofHours(3)), hour)
        val cache = cache(
            fusedPrecipitation = 1.5,
            fusedPrecipitationInterval = fusedInterval,
            sources = listOf(
                source(
                    ForecastProvider.ECMWF_OPEN_DATA,
                    ModelFamily.ECMWF_IFS,
                    hour,
                    precipitation = 1.0,
                    precipitationInterval = fusedInterval,
                ),
                source(
                    ForecastProvider.DWD_OPEN_DATA,
                    ModelFamily.DWD_ICON,
                    hour,
                    precipitation = 2.0,
                    precipitationInterval = otherInterval,
                ),
            ),
        )

        val comparison = buildModelComparisonData(cache)

        assertFalse(comparison.isAvailable(ModelComparisonParameter.PRECIPITATION))
        val fused = requireNotNull(fusedHourlyAt(cache, hour))
        assertEquals(
            1.0,
            sourceValueAt(cache.sourceForecasts.first(), fused, ModelComparisonParameter.PRECIPITATION),
            0.0,
        )
        assertNull(
            sourceValueAt(cache.sourceForecasts.last(), fused, ModelComparisonParameter.PRECIPITATION),
        )
    }

    @Test
    fun preservesFailedProviderPathInsideItsModelFamily() {
        val cache = cache(
            sources = listOf(
                source(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS, hour, temperature = 8.0),
            ),
            failed = listOf(
                ForecastSourceIdentity(ForecastProvider.OPEN_METEO, ModelFamily.ECMWF_IFS),
                ForecastSourceIdentity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS),
            ),
        )

        val comparison = buildModelComparisonData(cache)

        val ecmwf = comparison.families.first { it.modelFamily == ModelFamily.ECMWF_IFS }
        assertEquals(2, ecmwf.providerPaths.size)
        assertTrue(ecmwf.providerPaths.last().failed)
        val noaa = comparison.families.first { it.modelFamily == ModelFamily.NOAA_GFS }
        assertEquals(ForecastProvider.NOAA_NOMADS, noaa.providerPaths.single().provider)
        assertTrue(noaa.providerPaths.single().failed)
    }

    private fun cache(
        sources: List<SourceForecast>,
        failed: List<ForecastSourceIdentity> = emptyList(),
        fusedPrecipitation: Double? = null,
        fusedPrecipitationInterval: ForecastInterval? = null,
    ): ForecastCacheState {
        val fusedPoint = weather(
            time = hour,
            temperature = 10.0,
            precipitation = fusedPrecipitation,
            precipitationInterval = fusedPrecipitationInterval,
        )
        return ForecastCacheState(
            forecast = FusedForecast(
                location = location,
                generatedAt = generatedAt,
                hourly = listOf(
                    FusedHourlyForecast(
                        weather = fusedPoint,
                        providerCount = 2,
                        independentEvidenceCount = 2,
                        agreement = ModelAgreement.MEDIUM,
                    ),
                ),
            ),
            freshness = ForecastFreshness.FRESH,
            sourceForecasts = sources,
            failedSources = failed,
        )
    }

    private fun source(
        provider: ForecastProvider,
        family: ModelFamily,
        time: Instant,
        temperature: Double? = null,
        precipitation: Double? = null,
        precipitationInterval: ForecastInterval? = null,
    ): SourceForecast = SourceForecast(
        origin = ForecastOrigin(
            provider = provider,
            modelFamily = family,
            modelRun = generatedAt.minus(Duration.ofHours(6)),
            generatedAt = generatedAt,
        ),
        location = location,
        hourly = listOf(
            weather(
                time = time,
                temperature = temperature,
                precipitation = precipitation,
                precipitationInterval = precipitationInterval,
            ),
        ),
    )

    private fun weather(
        time: Instant,
        temperature: Double?,
        precipitation: Double?,
        precipitationInterval: ForecastInterval?,
    ) = HourlyWeatherPoint(
        time = time,
        temperatureC = temperature,
        feelsLikeC = null,
        dewPointC = null,
        humidityPercent = null,
        pressureSeaLevelHpa = null,
        windSpeedMps = null,
        windGustMps = null,
        windDirectionDegrees = null,
        precipitationMm = precipitation,
        precipitationProbabilityPercent = null,
        cloudCoverPercent = null,
        visibilityMeters = null,
        precipitationInterval = precipitationInterval,
    )
}
