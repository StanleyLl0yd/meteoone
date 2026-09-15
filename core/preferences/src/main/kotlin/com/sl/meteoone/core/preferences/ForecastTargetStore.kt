package com.sl.meteoone.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastTarget
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val FORECAST_TARGET_DATASTORE_NAME = "forecast_target"

private val LATITUDE_TENTHS = intPreferencesKey("latitude_tenths")
private val LONGITUDE_TENTHS = intPreferencesKey("longitude_tenths")
private val ELEVATION_METERS = intPreferencesKey("elevation_meters")
private val TIME_ZONE_ID = stringPreferencesKey("time_zone_id")

private val FORECAST_TARGET_CORRUPTION_HANDLER =
    ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

private val Context.forecastTargetDataStore: DataStore<Preferences> by preferencesDataStore(
    name = FORECAST_TARGET_DATASTORE_NAME,
    corruptionHandler = FORECAST_TARGET_CORRUPTION_HANDLER,
)

interface ForecastTargetStore {
    val target: Flow<ForecastTarget?>

    suspend fun set(target: ForecastTarget)

    suspend fun clear()

    companion object {
        fun android(context: Context): ForecastTargetStore =
            PreferencesForecastTargetStore(context.applicationContext.forecastTargetDataStore)
    }
}

internal class PreferencesForecastTargetStore(
    private val dataStore: DataStore<Preferences>,
) : ForecastTargetStore {
    override val target: Flow<ForecastTarget?> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map(::decodeTarget)

    override suspend fun set(target: ForecastTarget) {
        val latitudeTenths = target.coordinate.latitude.toTenthsExact()
        val longitudeTenths = target.coordinate.longitude.toTenthsExact()
        dataStore.edit { preferences ->
            preferences[LATITUDE_TENTHS] = latitudeTenths
            preferences[LONGITUDE_TENTHS] = longitudeTenths
            preferences[TIME_ZONE_ID] = target.timeZoneId
            target.elevationMeters?.let { elevation ->
                preferences[ELEVATION_METERS] = elevation
            } ?: preferences.remove(ELEVATION_METERS)
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(LATITUDE_TENTHS)
            preferences.remove(LONGITUDE_TENTHS)
            preferences.remove(ELEVATION_METERS)
            preferences.remove(TIME_ZONE_ID)
        }
    }
}

private fun decodeTarget(preferences: Preferences): ForecastTarget? = try {
    val latitudeTenths = preferences[LATITUDE_TENTHS] ?: return null
    val longitudeTenths = preferences[LONGITUDE_TENTHS] ?: return null
    val timeZoneId = preferences[TIME_ZONE_ID] ?: return null
    ForecastTarget(
        coordinate = ForecastCoordinate(
            latitude = latitudeTenths.toDegrees(),
            longitude = longitudeTenths.toDegrees(),
        ),
        elevationMeters = preferences[ELEVATION_METERS],
        timeZoneId = timeZoneId,
    )
} catch (_: IllegalArgumentException) {
    null
} catch (_: ClassCastException) {
    null
}

private fun Double.toTenthsExact(): Int =
    BigDecimal.valueOf(this).movePointRight(1).intValueExact()

private fun Int.toDegrees(): Double =
    BigDecimal.valueOf(toLong()).movePointLeft(1).toDouble()
