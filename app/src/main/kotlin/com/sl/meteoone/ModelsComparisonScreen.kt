package com.sl.meteoone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ForecastTarget
import com.sl.meteoone.core.model.ModelAgreement
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.repository.ForecastCacheState
import com.sl.meteoone.forecast.repository.ForecastRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

@Composable
internal fun ModelsComparisonContent(
    modifier: Modifier,
    target: ForecastTarget?,
    repository: ForecastRepository,
) {
    if (target == null) {
        ModelsMessageCard(
            modifier = modifier,
            message = stringResource(R.string.models_no_target),
        )
        return
    }

    val cacheLoad by produceState<ModelsCacheLoadState>(
        initialValue = ModelsCacheLoadState.Loading,
        key1 = repository,
        key2 = target.coordinate,
    ) {
        try {
            repository.observe(target.coordinate).collect { state ->
                value = ModelsCacheLoadState.Loaded(state)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = ModelsCacheLoadState.Failed
        }
    }

    when (val load = cacheLoad) {
        ModelsCacheLoadState.Loading -> ModelsProgress(modifier)
        ModelsCacheLoadState.Failed -> ModelsMessageCard(
            modifier = modifier,
            message = stringResource(R.string.models_cache_read_failed),
            isError = true,
        )
        is ModelsCacheLoadState.Loaded -> {
            val cache = load.cache
            if (cache == null) {
                ModelsMessageCard(
                    modifier = modifier,
                    message = stringResource(R.string.models_no_cache),
                )
            } else {
                ModelsSnapshot(
                    modifier = modifier,
                    cache = cache,
                    timeZoneId = target.timeZoneId,
                )
            }
        }
    }
}

@Composable
private fun ModelsSnapshot(
    modifier: Modifier,
    cache: ForecastCacheState,
    timeZoneId: String,
) {
    val comparison = remember(cache) { buildModelComparisonData(cache) }
    val availableParameters = ModelComparisonParameter.entries.filter(comparison::isAvailable)
    var selectedParameter by remember(cache.forecast.generatedAt) {
        mutableStateOf(
            availableParameters.firstOrNull() ?: ModelComparisonParameter.TEMPERATURE,
        )
    }
    val activeParameter = selectedParameter
        .takeIf(comparison::isAvailable)
        ?: availableParameters.firstOrNull()
        ?: ModelComparisonParameter.TEMPERATURE
    val comparableHours = comparison.hoursFor(activeParameter)
    var selectedHour by remember(cache.forecast.generatedAt, activeParameter) {
        mutableStateOf<Instant?>(null)
    }
    val activeHour = selectedHour
        ?.takeIf { candidate -> candidate in comparableHours }
        ?: comparableHours.firstOrNull()
    val fused = activeHour?.let { time -> fusedHourlyAt(cache, time) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.models_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = stringResource(R.string.models_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (cache.sourceForecasts.isEmpty() && cache.failedSources.isEmpty()) {
            item {
                ModelsMessageCard(
                    modifier = Modifier.fillMaxWidth(),
                    message = stringResource(R.string.models_legacy_cache),
                )
            }
        }

        item {
            Text(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .semantics { heading() },
                text = stringResource(R.string.models_parameter_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LazyRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ModelComparisonParameter.entries) { parameter ->
                    val enabled = comparison.isAvailable(parameter)
                    FilterChip(
                        selected = enabled && activeParameter == parameter,
                        onClick = { selectedParameter = parameter },
                        enabled = enabled,
                        label = { Text(parameterLabel(parameter)) },
                    )
                }
            }
            if (availableParameters.isEmpty()) {
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = stringResource(R.string.models_no_comparable_parameter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (comparableHours.isNotEmpty()) {
            item {
                Text(
                    modifier = Modifier.semantics { heading() },
                    text = stringResource(R.string.models_hour_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                LazyRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(comparableHours, key = { time -> time.epochSecond }) { time ->
                        FilterChip(
                            selected = activeHour == time,
                            onClick = { selectedHour = time },
                            label = {
                                Text(
                                    MODELS_HOUR_FORMATTER
                                        .withZone(safeZoneId(timeZoneId))
                                        .format(time),
                                )
                            },
                        )
                    }
                }
            }
        }

        item {
            FusionReferenceCard(
                cache = cache,
                parameter = activeParameter,
                fused = fused,
                timeZoneId = timeZoneId,
            )
        }

        items(
            items = comparison.families,
            key = { family -> family.modelFamily.name },
        ) { family ->
            ModelFamilyCard(
                family = family,
                fused = fused,
                parameter = activeParameter,
                timeZoneId = timeZoneId,
            )
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.models_exact_hour_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FusionReferenceCard(
    cache: ForecastCacheState,
    parameter: ModelComparisonParameter,
    fused: com.sl.meteoone.core.model.FusedHourlyForecast?,
    timeZoneId: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.models_fusion_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = stringResource(R.string.models_fusion_reference),
                style = MaterialTheme.typography.bodySmall,
            )
            val value = fused?.let { item -> comparisonValue(item.weather, parameter) }
            Text(
                modifier = Modifier.padding(top = 12.dp),
                text = parameterValue(parameter, value),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (fused != null) {
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = modelAgreementLabel(fused.agreement),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = stringResource(
                        R.string.value_evidence_compact,
                        fused.independentEvidenceCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = stringResource(
                        R.string.models_selected_hour,
                        formatModelInstant(fused.weather.time, timeZoneId),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = stringResource(R.string.models_no_selected_hour),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = stringResource(
                    R.string.forecast_generated,
                    formatModelInstant(cache.forecast.generatedAt, timeZoneId),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ModelFamilyCard(
    family: ModelFamilyComparison,
    fused: com.sl.meteoone.core.model.FusedHourlyForecast?,
    parameter: ModelComparisonParameter,
    timeZoneId: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = modelFamilyLabel(family.modelFamily),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = pluralStringResource(
                    R.plurals.models_provider_paths_count,
                    family.providerPaths.size,
                    family.providerPaths.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (family.providerPaths.isEmpty()) {
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    text = stringResource(R.string.models_no_provider_path),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                family.providerPaths.forEach { path ->
                    ProviderPathBlock(
                        path = path,
                        family = family.modelFamily,
                        fused = fused,
                        parameter = parameter,
                        timeZoneId = timeZoneId,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderPathBlock(
    path: ModelProviderPath,
    family: ModelFamily,
    fused: com.sl.meteoone.core.model.FusedHourlyForecast?,
    parameter: ModelComparisonParameter,
    timeZoneId: String,
) {
    val source = path.sourceForecast
    val value = if (source != null && fused != null) {
        sourceValueAt(source, fused, parameter)
    } else {
        null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = providerLabel(path.provider),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = stringResource(
                        R.string.models_model_family_path,
                        modelFamilyLabel(family),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    modifier = Modifier.padding(top = 6.dp),
                    text = when {
                        path.failed -> stringResource(R.string.models_source_failed)
                        source == null -> stringResource(R.string.models_source_unavailable)
                        fused == null -> stringResource(R.string.models_source_cached)
                        value == null -> stringResource(R.string.models_source_gap)
                        else -> stringResource(R.string.models_source_available)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            Text(
                modifier = Modifier.padding(top = 10.dp),
                text = parameterValue(parameter, value),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )

            if (source != null) {
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = source.origin.modelRun?.let { modelRun ->
                        stringResource(
                            R.string.models_model_run,
                            formatModelInstant(modelRun, timeZoneId),
                        )
                    } ?: stringResource(R.string.models_model_run_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = stringResource(
                        R.string.models_source_generated,
                        formatModelInstant(source.origin.generatedAt, timeZoneId),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ModelsMessageCard(
    modifier: Modifier,
    message: String,
    isError: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Text(
            modifier = Modifier.padding(16.dp),
            text = message,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ModelsProgress(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun parameterLabel(parameter: ModelComparisonParameter): String = when (parameter) {
    ModelComparisonParameter.TEMPERATURE -> stringResource(R.string.models_parameter_temperature)
    ModelComparisonParameter.PRECIPITATION -> stringResource(R.string.models_parameter_precipitation)
    ModelComparisonParameter.WIND -> stringResource(R.string.models_parameter_wind)
    ModelComparisonParameter.PRESSURE -> stringResource(R.string.models_parameter_pressure)
}

@Composable
private fun parameterValue(
    parameter: ModelComparisonParameter,
    value: Double?,
): String {
    if (value == null) return stringResource(R.string.value_unavailable)
    return when (parameter) {
        ModelComparisonParameter.TEMPERATURE ->
            stringResource(R.string.models_value_temperature, value)
        ModelComparisonParameter.PRECIPITATION ->
            stringResource(R.string.models_value_precipitation, value)
        ModelComparisonParameter.WIND ->
            stringResource(R.string.models_value_wind, value)
        ModelComparisonParameter.PRESSURE ->
            stringResource(R.string.models_value_pressure, value)
    }
}

@Composable
private fun modelFamilyLabel(family: ModelFamily): String = when (family) {
    ModelFamily.ECMWF_IFS -> stringResource(R.string.models_family_ecmwf)
    ModelFamily.DWD_ICON -> stringResource(R.string.models_family_dwd)
    ModelFamily.NOAA_GFS -> stringResource(R.string.models_family_noaa)
    ModelFamily.UNKNOWN -> stringResource(R.string.models_family_unknown)
}

@Composable
private fun providerLabel(provider: ForecastProvider): String = when (provider) {
    ForecastProvider.OPEN_METEO -> stringResource(R.string.models_provider_open_meteo)
    ForecastProvider.MET_NORWAY -> stringResource(R.string.models_provider_met_norway)
    ForecastProvider.NOAA_NOMADS -> stringResource(R.string.models_provider_noaa)
    ForecastProvider.ECMWF_OPEN_DATA -> stringResource(R.string.models_provider_ecmwf)
    ForecastProvider.DWD_OPEN_DATA -> stringResource(R.string.models_provider_dwd)
    ForecastProvider.UNKNOWN -> stringResource(R.string.models_provider_unknown)
}

@Composable
private fun modelAgreementLabel(agreement: ModelAgreement): String = when (agreement) {
    ModelAgreement.HIGH -> stringResource(R.string.agreement_high)
    ModelAgreement.MEDIUM -> stringResource(R.string.agreement_medium)
    ModelAgreement.LOW -> stringResource(R.string.agreement_low)
    ModelAgreement.INSUFFICIENT -> stringResource(R.string.agreement_insufficient)
}

private fun formatModelInstant(
    instant: Instant,
    timeZoneId: String,
): String = MODELS_DATE_TIME_FORMATTER
    .withZone(safeZoneId(timeZoneId))
    .format(instant)

private fun safeZoneId(timeZoneId: String): ZoneId =
    runCatching { ZoneId.of(timeZoneId) }.getOrElse { ZoneId.systemDefault() }

private sealed interface ModelsCacheLoadState {
    data object Loading : ModelsCacheLoadState
    data class Loaded(val cache: ForecastCacheState?) : ModelsCacheLoadState
    data object Failed : ModelsCacheLoadState
}

private val MODELS_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val MODELS_HOUR_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE HH:mm")
