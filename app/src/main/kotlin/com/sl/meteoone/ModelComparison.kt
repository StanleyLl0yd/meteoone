package com.sl.meteoone

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.forecast.repository.ForecastCacheState
import com.sl.meteoone.forecast.repository.ForecastSourceIdentity
import java.time.Instant

internal enum class ModelComparisonParameter {
    TEMPERATURE,
    PRECIPITATION,
    WIND,
    PRESSURE,
}

internal data class ModelProviderPath(
    val provider: ForecastProvider,
    val sourceForecast: SourceForecast?,
    val failed: Boolean,
) {
    init {
        require(sourceForecast == null || sourceForecast.origin.provider == provider) {
            "Provider path must preserve source provider identity"
        }
        require(!failed || sourceForecast == null) {
            "A provider path cannot be successful and failed simultaneously"
        }
    }
}

internal data class ModelFamilyComparison(
    val modelFamily: ModelFamily,
    val providerPaths: List<ModelProviderPath>,
)

internal data class ModelComparisonData(
    val families: List<ModelFamilyComparison>,
    private val comparableHours: Map<ModelComparisonParameter, List<Instant>>,
) {
    fun hoursFor(parameter: ModelComparisonParameter): List<Instant> =
        comparableHours[parameter].orEmpty()

    fun isAvailable(parameter: ModelComparisonParameter): Boolean =
        hoursFor(parameter).isNotEmpty()
}

internal fun buildModelComparisonData(cache: ForecastCacheState): ModelComparisonData {
    val families = COMPARISON_MODEL_ORDER.map { family ->
        val successful = cache.sourceForecasts
            .filter { source -> source.origin.modelFamily == family }
            .map { source ->
                ModelProviderPath(
                    provider = source.origin.provider,
                    sourceForecast = source,
                    failed = false,
                )
            }
        val failed = cache.failedSources
            .filter { identity -> identity.modelFamily == family }
            .map { identity ->
                ModelProviderPath(
                    provider = identity.provider,
                    sourceForecast = null,
                    failed = true,
                )
            }
        ModelFamilyComparison(
            modelFamily = family,
            providerPaths = (successful + failed).sortedBy { path -> providerOrder(path.provider) },
        )
    }

    val comparableHours = ModelComparisonParameter.entries.associateWith { parameter ->
        cache.forecast.hourly.mapNotNull { fused ->
            if (comparisonValue(fused.weather, parameter) == null) {
                return@mapNotNull null
            }
            val comparableFamilies = COMPARISON_MODEL_ORDER.count { family ->
                cache.sourceForecasts.any { source ->
                    source.origin.modelFamily == family &&
                        sourceValueAt(source, fused, parameter) != null
                }
            }
            fused.weather.time.takeIf { comparableFamilies >= MIN_COMPARABLE_MODEL_FAMILIES }
        }
    }

    return ModelComparisonData(
        families = families,
        comparableHours = comparableHours,
    )
}

internal fun fusedHourlyAt(
    cache: ForecastCacheState,
    time: Instant,
): FusedHourlyForecast? = cache.forecast.hourly.firstOrNull { item -> item.weather.time == time }

internal fun sourceValueAt(
    source: SourceForecast,
    fused: FusedHourlyForecast,
    parameter: ModelComparisonParameter,
): Double? {
    val sourcePoint = source.hourly.firstOrNull { point ->
        point.time == fused.weather.time
    } ?: return null

    if (parameter == ModelComparisonParameter.PRECIPITATION) {
        val fusedInterval = fused.weather.precipitationInterval ?: return null
        if (sourcePoint.precipitationInterval != fusedInterval) return null
    }
    return comparisonValue(sourcePoint, parameter)
}

internal fun failedIdentityFor(
    path: ModelProviderPath,
    family: ModelFamily,
): ForecastSourceIdentity? = if (path.failed) {
    ForecastSourceIdentity(path.provider, family)
} else {
    null
}

internal fun comparisonValue(
    point: HourlyWeatherPoint,
    parameter: ModelComparisonParameter,
): Double? = when (parameter) {
    ModelComparisonParameter.TEMPERATURE -> point.temperatureC
    ModelComparisonParameter.PRECIPITATION -> point.precipitationMm
    ModelComparisonParameter.WIND -> point.windSpeedMps
    ModelComparisonParameter.PRESSURE -> point.pressureSeaLevelHpa
}

private fun providerOrder(provider: ForecastProvider): Int = when (provider) {
    ForecastProvider.ECMWF_OPEN_DATA -> 0
    ForecastProvider.DWD_OPEN_DATA -> 1
    ForecastProvider.NOAA_NOMADS -> 2
    ForecastProvider.OPEN_METEO -> 3
    ForecastProvider.MET_NORWAY -> 4
    ForecastProvider.UNKNOWN -> 5
}

internal val COMPARISON_MODEL_ORDER: List<ModelFamily> = listOf(
    ModelFamily.ECMWF_IFS,
    ModelFamily.DWD_ICON,
    ModelFamily.NOAA_GFS,
)

private const val MIN_COMPARABLE_MODEL_FAMILIES = 2
