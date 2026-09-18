package com.sl.meteoone

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sl.meteoone.core.location.AndroidCurrentLocationClient
import com.sl.meteoone.core.location.CurrentLocationResult
import com.sl.meteoone.core.location.LocationRequestHandle
import com.sl.meteoone.core.model.ForecastTarget
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.ModelAgreement
import com.sl.meteoone.core.model.WeatherCondition
import com.sl.meteoone.core.preferences.ForecastTargetStore
import com.sl.meteoone.forecast.repository.ForecastCacheState
import com.sl.meteoone.forecast.repository.ForecastFreshness
import com.sl.meteoone.forecast.repository.ForecastRefreshResult
import com.sl.meteoone.forecast.repository.ForecastRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun AlphaForecastScreen(
    repository: ForecastRepository,
    targetStore: ForecastTargetStore,
    locationClient: AndroidCurrentLocationClient,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var operation by remember { mutableStateOf<AlphaOperation>(AlphaOperation.Idle) }
    var locationRequest by remember { mutableStateOf<LocationRequestHandle?>(null) }
    var targetReloadRevision by remember { mutableStateOf(0L) }
    var destination by remember { mutableStateOf(ProductDestination.FORECAST) }

    val targetLoad by produceState<TargetLoadState>(
        initialValue = TargetLoadState.Loading,
        key1 = targetStore,
        key2 = targetReloadRevision,
    ) {
        try {
            targetStore.target.collect { target ->
                value = TargetLoadState.Loaded(target)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = TargetLoadState.Failed
        }
    }

    fun refresh(target: ForecastTarget) {
        if (operation.isBusy) return
        operation = AlphaOperation.Refreshing
        scope.launch {
            operation = AlphaOperation.RefreshFinished(
                repository.refresh(
                    coordinate = target.coordinate,
                    elevationMeters = target.elevationMeters,
                    timeZoneId = target.timeZoneId,
                ),
            )
        }
    }

    fun requestCurrentApproximateLocation() {
        if (operation.isBusy) return
        operation = AlphaOperation.Locating
        locationRequest?.cancel()
        locationRequest = locationClient.requestCurrentLocation { result ->
            locationRequest = null
            when (result) {
                is CurrentLocationResult.Available -> scope.launch {
                    val target = ForecastTarget(
                        coordinate = result.coordinate,
                        elevationMeters = null,
                        timeZoneId = ZoneId.systemDefault().id,
                    )
                    try {
                        targetStore.set(target)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        operation = AlphaOperation.TargetPersistenceFailed
                        return@launch
                    }
                    targetReloadRevision += 1L
                    operation = AlphaOperation.Idle
                    refresh(target)
                }

                is CurrentLocationResult.Unavailable -> {
                    operation = AlphaOperation.LocationUnavailable(result.reason)
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            requestCurrentApproximateLocation()
        } else {
            operation = AlphaOperation.LocationUnavailable(
                CurrentLocationResult.Reason.PERMISSION_REQUIRED,
            )
        }
    }

    fun chooseCurrentApproximateLocation() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            requestCurrentApproximateLocation()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    DisposableEffect(locationClient) {
        onDispose {
            locationRequest?.cancel()
            locationRequest = null
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            ProductNavigationBar(
                selected = destination,
                onSelected = { selected -> destination = selected },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = MAX_CONTENT_WIDTH)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        when (destination) {
                            ProductDestination.FORECAST -> R.string.forecast_screen_subtitle
                            ProductDestination.MODELS -> R.string.models_screen_subtitle
                            ProductDestination.SETTINGS -> R.string.settings_screen_subtitle
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                when (destination) {
                ProductDestination.FORECAST -> when (val load = targetLoad) {
                    TargetLoadState.Loading -> CenteredProgress()
                    TargetLoadState.Failed -> TargetStoreFailure(
                        operation = operation,
                        onUseLocation = ::chooseCurrentApproximateLocation,
                    )
                    is TargetLoadState.Loaded -> {
                        val target = load.target
                        if (target == null) {
                            NoTargetContent(
                                operation = operation,
                                onUseLocation = ::chooseCurrentApproximateLocation,
                            )
                        } else {
                            TargetForecastContent(
                                modifier = Modifier.weight(1f),
                                target = target,
                                repository = repository,
                                operation = operation,
                                onRefresh = { refresh(target) },
                                onUseLocation = ::chooseCurrentApproximateLocation,
                            )
                        }
                    }
                }

                ProductDestination.MODELS -> when (val load = targetLoad) {
                    TargetLoadState.Loading -> CenteredProgress()
                    TargetLoadState.Failed -> TargetStoreFailure(
                        operation = operation,
                        onUseLocation = ::chooseCurrentApproximateLocation,
                    )
                    is TargetLoadState.Loaded -> ModelsComparisonContent(
                        modifier = Modifier.weight(1f),
                        target = load.target,
                        repository = repository,
                    )
                }

                    ProductDestination.SETTINGS -> SettingsContent(
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductNavigationBar(
    selected: ProductDestination,
    onSelected: (ProductDestination) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == ProductDestination.FORECAST,
            onClick = { onSelected(ProductDestination.FORECAST) },
            icon = {},
            label = { Text(stringResource(R.string.nav_forecast)) },
        )
        NavigationBarItem(
            selected = selected == ProductDestination.MODELS,
            onClick = { onSelected(ProductDestination.MODELS) },
            icon = {},
            label = { Text(stringResource(R.string.nav_models)) },
        )
        NavigationBarItem(
            selected = selected == ProductDestination.SETTINGS,
            onClick = { onSelected(ProductDestination.SETTINGS) },
            icon = {},
            label = { Text(stringResource(R.string.nav_settings)) },
        )
    }
}

@Composable
private fun TargetForecastContent(
    modifier: Modifier,
    target: ForecastTarget,
    repository: ForecastRepository,
    operation: AlphaOperation,
    onRefresh: () -> Unit,
    onUseLocation: () -> Unit,
) {
    val cacheLoad by produceState<CacheLoadState>(
        initialValue = CacheLoadState.Loading,
        key1 = repository,
        key2 = target.coordinate,
    ) {
        try {
            repository.observe(target.coordinate).collect { state ->
                value = CacheLoadState.Loaded(state)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = CacheLoadState.Failed
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.forecast_location_approximate),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(
                R.string.forecast_target_coordinate,
                target.coordinate.latitude,
                target.coordinate.longitude,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRefresh,
                enabled = !operation.isBusy,
            ) {
                Text(stringResource(R.string.action_refresh))
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onUseLocation,
                enabled = !operation.isBusy,
            ) {
                Text(stringResource(R.string.action_update_location))
            }
        }
        OperationMessage(operation)
        Spacer(Modifier.height(12.dp))

        when (val load = cacheLoad) {
            CacheLoadState.Loading -> CenteredProgress()
            CacheLoadState.Failed -> Text(
                text = stringResource(R.string.forecast_cache_read_failed),
                color = MaterialTheme.colorScheme.error,
            )
            is CacheLoadState.Loaded -> {
                val cache = load.cache
                if (cache == null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            modifier = Modifier.padding(16.dp),
                            text = stringResource(R.string.forecast_no_cache),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    ForecastSnapshot(
                        modifier = Modifier.weight(1f),
                        cache = cache,
                        timeZoneId = target.timeZoneId,
                    )
                }
            }
        }
    }
}

@Composable
private fun ForecastSnapshot(
    modifier: Modifier,
    cache: ForecastCacheState,
    timeZoneId: String,
) {
    val forecast = cache.forecast
    val current = forecast.hourly.firstOrNull()
    val dayGroups = remember(forecast.hourly, timeZoneId) {
        groupHourlyByLocalDay(forecast.hourly, timeZoneId)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CurrentConditionsCard(
                item = current,
                timeZoneId = timeZoneId,
            )
        }
        item {
            ForecastFreshnessCard(
                cache = cache,
                timeZoneId = timeZoneId,
            )
        }
        item {
            DailyForecastSummarySection(
                items = forecast.hourly,
                timeZoneId = timeZoneId,
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.hourly_forecast_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.forecast_hours_count,
                    forecast.hourly.size,
                    forecast.hourly.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(
            items = dayGroups,
            key = { group -> group.date.toEpochDay() },
        ) { group ->
            HourlyDayRail(
                group = group,
                timeZoneId = timeZoneId,
            )
        }
    }
}

@Composable
private fun CurrentConditionsCard(
    item: FusedHourlyForecast?,
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
                text = stringResource(R.string.current_conditions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (item == null) {
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    text = stringResource(R.string.value_unavailable),
                    style = MaterialTheme.typography.displayMedium,
                )
                return@Column
            }

            val weather = item.weather
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = weather.temperatureC?.let { value ->
                    stringResource(R.string.value_temperature_c, value)
                } ?: stringResource(R.string.value_unavailable),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = conditionLabel(weather.condition),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = stringResource(
                    R.string.current_conditions_time,
                    formatInstant(weather.time, timeZoneId, HOUR_FORMATTER),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = weather.precipitationMm?.let { value ->
                        stringResource(R.string.value_precipitation_mm, value)
                    } ?: stringResource(R.string.value_precipitation_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = weather.windSpeedMps?.let { value ->
                        stringResource(R.string.value_wind_mps, value)
                    } ?: stringResource(R.string.value_wind_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ForecastFreshnessCard(
    cache: ForecastCacheState,
    timeZoneId: String,
) {
    val freshnessText = when (cache.freshness) {
        ForecastFreshness.FRESH -> stringResource(R.string.freshness_fresh)
        ForecastFreshness.STALE -> stringResource(R.string.freshness_stale)
        ForecastFreshness.EXPIRED -> stringResource(R.string.freshness_expired)
    }
    val containerColor = when (cache.freshness) {
        ForecastFreshness.FRESH -> MaterialTheme.colorScheme.secondaryContainer
        ForecastFreshness.STALE -> MaterialTheme.colorScheme.tertiaryContainer
        ForecastFreshness.EXPIRED -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (cache.freshness) {
        ForecastFreshness.FRESH -> MaterialTheme.colorScheme.onSecondaryContainer
        ForecastFreshness.STALE -> MaterialTheme.colorScheme.onTertiaryContainer
        ForecastFreshness.EXPIRED -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = freshnessText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = stringResource(
                    R.string.forecast_generated,
                    formatInstant(cache.forecast.generatedAt, timeZoneId, DATE_TIME_FORMATTER),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HourlyDayRail(
    group: HourlyDayGroup,
    timeZoneId: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = DAY_FORMATTER.format(group.date),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = pluralStringResource(
                    R.plurals.hourly_day_count,
                    group.items.size,
                    group.items.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = group.items,
                key = { item -> item.weather.time.toEpochMilli() },
            ) { item ->
                HourlyForecastCard(
                    item = item,
                    timeZoneId = timeZoneId,
                )
            }
        }
    }
}

@Composable
private fun HourlyForecastCard(
    item: FusedHourlyForecast,
    timeZoneId: String,
) {
    val weather = item.weather
    Card(
        modifier = Modifier.widthIn(min = 152.dp, max = 220.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = formatInstant(weather.time, timeZoneId, TIME_FORMATTER),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                modifier = Modifier.padding(top = 10.dp),
                text = weather.temperatureC?.let { value ->
                    stringResource(R.string.value_temperature_c, value)
                } ?: stringResource(R.string.value_unavailable),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = conditionLabel(weather.condition),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.padding(top = 12.dp),
                text = weather.precipitationMm?.let { value ->
                    stringResource(R.string.value_precipitation_mm, value)
                } ?: stringResource(R.string.value_precipitation_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = weather.windSpeedMps?.let { value ->
                    stringResource(R.string.value_wind_mps, value)
                } ?: stringResource(R.string.value_wind_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modifier = Modifier.padding(top = 10.dp),
                text = agreementLabel(item.agreement),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = stringResource(
                    R.string.value_evidence_compact,
                    item.independentEvidenceCount,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoTargetContent(
    operation: AlphaOperation,
    onUseLocation: () -> Unit,
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
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = stringResource(R.string.onboarding_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                modifier = Modifier.padding(top = 12.dp),
                text = stringResource(R.string.forecast_location_explanation),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = stringResource(R.string.forecast_privacy_note),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                modifier = Modifier.padding(top = 20.dp),
                onClick = onUseLocation,
                enabled = !operation.isBusy,
            ) {
                Text(stringResource(R.string.action_use_approximate_location))
            }
            OperationMessage(operation)
        }
    }
}

@Composable
private fun TargetStoreFailure(
    operation: AlphaOperation,
    onUseLocation: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.forecast_target_read_failed),
            color = MaterialTheme.colorScheme.error,
        )
        Button(
            modifier = Modifier.padding(top = 16.dp),
            onClick = onUseLocation,
            enabled = !operation.isBusy,
        ) {
            Text(stringResource(R.string.action_use_approximate_location))
        }
        OperationMessage(operation)
    }
}

@Composable
private fun CenteredProgress() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun OperationMessage(operation: AlphaOperation) {
    val text = when (operation) {
        AlphaOperation.Idle -> null
        AlphaOperation.Locating -> stringResource(R.string.status_locating)
        AlphaOperation.Refreshing -> stringResource(R.string.status_refreshing)
        AlphaOperation.TargetPersistenceFailed -> stringResource(R.string.status_target_save_failed)
        is AlphaOperation.LocationUnavailable -> when (operation.reason) {
            CurrentLocationResult.Reason.PERMISSION_REQUIRED ->
                stringResource(R.string.status_location_permission_required)
            CurrentLocationResult.Reason.PROVIDER_UNAVAILABLE ->
                stringResource(R.string.status_location_provider_unavailable)
            CurrentLocationResult.Reason.TIMEOUT ->
                stringResource(R.string.status_location_timeout)
            CurrentLocationResult.Reason.CANCELLED ->
                stringResource(R.string.status_location_cancelled)
            CurrentLocationResult.Reason.PLATFORM_FAILURE ->
                stringResource(R.string.status_location_failed)
        }
        is AlphaOperation.RefreshFinished -> when (operation.result) {
            ForecastRefreshResult.Updated -> stringResource(R.string.status_forecast_updated)
            ForecastRefreshResult.UpdatedWithDegradation ->
                stringResource(R.string.status_forecast_updated_degraded)
            ForecastRefreshResult.Unavailable -> stringResource(R.string.status_forecast_unavailable)
            ForecastRefreshResult.Failed -> stringResource(R.string.status_forecast_failed)
        }
    }
    if (text != null) {
        Text(
            modifier = Modifier.padding(top = 8.dp),
            text = text,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun conditionLabel(condition: WeatherCondition): String = when (condition) {
    WeatherCondition.UNKNOWN -> stringResource(R.string.condition_unknown)
    WeatherCondition.CLEAR -> stringResource(R.string.condition_clear)
    WeatherCondition.PARTLY_CLOUDY -> stringResource(R.string.condition_partly_cloudy)
    WeatherCondition.CLOUDY -> stringResource(R.string.condition_cloudy)
    WeatherCondition.FOG -> stringResource(R.string.condition_fog)
    WeatherCondition.RAIN -> stringResource(R.string.condition_rain)
    WeatherCondition.HEAVY_RAIN -> stringResource(R.string.condition_heavy_rain)
    WeatherCondition.SNOW -> stringResource(R.string.condition_snow)
    WeatherCondition.SLEET -> stringResource(R.string.condition_sleet)
    WeatherCondition.THUNDERSTORM -> stringResource(R.string.condition_thunderstorm)
}

@Composable
private fun agreementLabel(agreement: ModelAgreement): String = when (agreement) {
    ModelAgreement.HIGH -> stringResource(R.string.agreement_high)
    ModelAgreement.MEDIUM -> stringResource(R.string.agreement_medium)
    ModelAgreement.LOW -> stringResource(R.string.agreement_low)
    ModelAgreement.INSUFFICIENT -> stringResource(R.string.agreement_insufficient)
}

private fun groupHourlyByLocalDay(
    items: List<FusedHourlyForecast>,
    timeZoneId: String,
): List<HourlyDayGroup> {
    val zone = runCatching { ZoneId.of(timeZoneId) }
        .getOrElse { ZoneId.systemDefault() }
    return items
        .groupBy { item -> item.weather.time.atZone(zone).toLocalDate() }
        .map { (date, groupedItems) ->
            HourlyDayGroup(date = date, items = groupedItems)
        }
}

private fun formatInstant(
    instant: Instant,
    timeZoneId: String,
    formatter: DateTimeFormatter,
): String {
    val zone = runCatching { ZoneId.of(timeZoneId) }
        .getOrElse { ZoneId.systemDefault() }
    return formatter.withZone(zone).format(instant)
}

private data class HourlyDayGroup(
    val date: LocalDate,
    val items: List<FusedHourlyForecast>,
)

private enum class ProductDestination {
    FORECAST,
    MODELS,
    SETTINGS,
}

private sealed interface TargetLoadState {
    data object Loading : TargetLoadState
    data class Loaded(val target: ForecastTarget?) : TargetLoadState
    data object Failed : TargetLoadState
}

private sealed interface CacheLoadState {
    data object Loading : CacheLoadState
    data class Loaded(val cache: ForecastCacheState?) : CacheLoadState
    data object Failed : CacheLoadState
}

private sealed interface AlphaOperation {
    val isBusy: Boolean
        get() = this === Locating || this === Refreshing

    data object Idle : AlphaOperation
    data object Locating : AlphaOperation
    data object Refreshing : AlphaOperation
    data object TargetPersistenceFailed : AlphaOperation
    data class LocationUnavailable(val reason: CurrentLocationResult.Reason) : AlphaOperation
    data class RefreshFinished(val result: ForecastRefreshResult) : AlphaOperation
}

private val MAX_CONTENT_WIDTH = 840.dp

private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val HOUR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
