package com.sl.meteoone

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sl.meteoone.core.location.AndroidCurrentLocationClient
import com.sl.meteoone.core.location.CurrentLocationResult
import com.sl.meteoone.core.location.LocationRequestHandle
import com.sl.meteoone.core.model.ForecastTarget
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.WeatherCondition
import com.sl.meteoone.core.preferences.ForecastTargetStore
import com.sl.meteoone.forecast.repository.ForecastCacheState
import com.sl.meteoone.forecast.repository.ForecastFreshness
import com.sl.meteoone.forecast.repository.ForecastRefreshResult
import com.sl.meteoone.forecast.repository.ForecastRepository
import java.time.Instant
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

    Scaffold(modifier = Modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.alpha_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))

            when (val load = targetLoad) {
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
        }
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
            text = stringResource(
                R.string.forecast_target_coordinate,
                target.coordinate.latitude,
                target.coordinate.longitude,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.forecast_privacy_note),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onRefresh,
                enabled = !operation.isBusy,
            ) {
                Text(stringResource(R.string.action_refresh))
            }
            OutlinedButton(
                onClick = onUseLocation,
                enabled = !operation.isBusy,
            ) {
                Text(stringResource(R.string.action_update_location))
            }
        }
        OperationMessage(operation)
        Spacer(Modifier.height(8.dp))

        when (val load = cacheLoad) {
            CacheLoadState.Loading -> CenteredProgress()
            CacheLoadState.Failed -> Text(
                text = stringResource(R.string.forecast_cache_read_failed),
                color = MaterialTheme.colorScheme.error,
            )
            is CacheLoadState.Loaded -> {
                val cache = load.cache
                if (cache == null) {
                    Text(
                        text = stringResource(R.string.forecast_no_cache),
                        style = MaterialTheme.typography.bodyLarge,
                    )
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
    val freshnessText = when (cache.freshness) {
        ForecastFreshness.FRESH -> stringResource(R.string.freshness_fresh)
        ForecastFreshness.STALE -> stringResource(R.string.freshness_stale)
        ForecastFreshness.EXPIRED -> stringResource(R.string.freshness_expired)
    }
    val freshnessColor = when (cache.freshness) {
        ForecastFreshness.FRESH -> MaterialTheme.colorScheme.primary
        ForecastFreshness.STALE -> MaterialTheme.colorScheme.tertiary
        ForecastFreshness.EXPIRED -> MaterialTheme.colorScheme.error
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = freshnessText,
            style = MaterialTheme.typography.titleMedium,
            color = freshnessColor,
        )
        Text(
            text = stringResource(
                R.string.forecast_generated,
                formatInstant(forecast.generatedAt, timeZoneId, DATE_TIME_FORMATTER),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(R.string.forecast_hours_count, forecast.hourly.size),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                items = forecast.hourly,
                key = { item -> item.weather.time.toEpochMilli() },
            ) { item ->
                HourlyForecastRow(item = item, timeZoneId = timeZoneId)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HourlyForecastRow(
    item: FusedHourlyForecast,
    timeZoneId: String,
) {
    val weather = item.weather
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatInstant(weather.time, timeZoneId, HOUR_FORMATTER),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = conditionLabel(weather.condition),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = weather.temperatureC?.let { value ->
                    stringResource(R.string.value_temperature_c, value)
                } ?: stringResource(R.string.value_unavailable),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = weather.precipitationMm?.let { value ->
                    stringResource(R.string.value_precipitation_mm, value)
                } ?: stringResource(R.string.value_precipitation_unavailable),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = weather.windSpeedMps?.let { value ->
                    stringResource(R.string.value_wind_mps, value)
                } ?: stringResource(R.string.value_wind_unavailable),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.value_evidence,
                    item.independentEvidenceCount,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NoTargetContent(
    operation: AlphaOperation,
    onUseLocation: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.forecast_no_target),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            modifier = Modifier.padding(top = 8.dp),
            text = stringResource(R.string.forecast_location_explanation),
            style = MaterialTheme.typography.bodyMedium,
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

private fun formatInstant(
    instant: Instant,
    timeZoneId: String,
    formatter: DateTimeFormatter,
): String {
    val zone = runCatching { ZoneId.of(timeZoneId) }
        .getOrElse { ZoneId.systemDefault() }
    return formatter.withZone(zone).format(instant)
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

private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val HOUR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")
