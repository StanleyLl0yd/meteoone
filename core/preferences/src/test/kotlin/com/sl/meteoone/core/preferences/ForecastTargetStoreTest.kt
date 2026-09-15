package com.sl.meteoone.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastTarget
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ForecastTargetStoreTest {
    @Test
    fun targetRoundTripsAcrossDataStoreReopen() = runBlocking {
        val directory = Files.createTempDirectory("meteoone-target").toFile()
        val file = File(directory, "forecast_target.preferences_pb")
        val target = ForecastTarget(
            coordinate = ForecastCoordinate(59.9, 30.3),
            elevationMeters = 14,
            timeZoneId = "Europe/Moscow",
        )

        val firstScope = testScope()
        val firstDataStore = createDataStore(file, firstScope)
        PreferencesForecastTargetStore(firstDataStore).set(target)
        assertEquals(target, PreferencesForecastTargetStore(firstDataStore).target.first())
        firstScope.close()

        val secondScope = testScope()
        try {
            val reopened = PreferencesForecastTargetStore(createDataStore(file, secondScope))
            assertEquals(target, reopened.target.first())
        } finally {
            secondScope.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun persistsCoordinatesOnlyAsIntegerTenths() = runBlocking {
        withStore { dataStore, store ->
            store.set(
                ForecastTarget(
                    coordinate = ForecastCoordinate(59.9, -30.3),
                    elevationMeters = null,
                    timeZoneId = "UTC",
                ),
            )

            val preferences = dataStore.data.first()
            assertEquals(599, preferences[intPreferencesKey("latitude_tenths")])
            assertEquals(-303, preferences[intPreferencesKey("longitude_tenths")])
            assertNull(preferences[intPreferencesKey("elevation_meters")])
            assertEquals("UTC", preferences[stringPreferencesKey("time_zone_id")])
        }
    }

    @Test
    fun replacingTargetClearsPriorOptionalElevation() = runBlocking {
        withStore { _, store ->
            store.set(
                ForecastTarget(
                    coordinate = ForecastCoordinate(59.9, 30.3),
                    elevationMeters = 14,
                    timeZoneId = "Europe/Moscow",
                ),
            )
            val replacement = ForecastTarget(
                coordinate = ForecastCoordinate(60.0, 30.4),
                elevationMeters = null,
                timeZoneId = "Europe/Moscow",
            )
            store.set(replacement)

            assertEquals(replacement, store.target.first())
        }
    }

    @Test
    fun clearRemovesWholeTarget() = runBlocking {
        withStore { dataStore, store ->
            store.set(
                ForecastTarget(
                    coordinate = ForecastCoordinate(59.9, 30.3),
                    elevationMeters = 14,
                    timeZoneId = "Europe/Moscow",
                ),
            )
            store.clear()

            assertNull(store.target.first())
            assertEquals(emptyMap(), dataStore.data.first().asMap())
        }
    }

    @Test
    fun incompletePreferencesFailClosedToNoTarget() = runBlocking {
        withStore { dataStore, store ->
            dataStore.edit { preferences ->
                preferences[intPreferencesKey("latitude_tenths")] = 599
                preferences[stringPreferencesKey("time_zone_id")] = "UTC"
            }

            assertNull(store.target.first())
        }
    }

    @Test
    fun invalidCoordinateFailsClosedToNoTarget() = runBlocking {
        withStore { dataStore, store ->
            dataStore.edit { preferences ->
                preferences[intPreferencesKey("latitude_tenths")] = 901
                preferences[intPreferencesKey("longitude_tenths")] = 303
                preferences[stringPreferencesKey("time_zone_id")] = "UTC"
            }

            assertNull(store.target.first())
        }
    }

    @Test
    fun invalidTimeZoneMetadataFailsClosedToNoTarget() = runBlocking {
        withStore { dataStore, store ->
            dataStore.edit { preferences ->
                preferences[intPreferencesKey("latitude_tenths")] = 599
                preferences[intPreferencesKey("longitude_tenths")] = 303
                preferences[stringPreferencesKey("time_zone_id")] = "   "
            }

            assertNull(store.target.first())
        }
    }

    @Test
    fun wrongPreferenceTypeFailsClosedToNoTarget() = runBlocking {
        withStore { dataStore, store ->
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("latitude_tenths")] = "599"
                preferences[intPreferencesKey("longitude_tenths")] = 303
                preferences[stringPreferencesKey("time_zone_id")] = "UTC"
            }

            assertNull(store.target.first())
        }
    }

    @Test
    fun corruptPreferencesFileIsReplacedWithNoTarget() = runBlocking {
        val directory = Files.createTempDirectory("meteoone-target-corrupt").toFile()
        val file = File(directory, "forecast_target.preferences_pb")
        file.writeBytes(byteArrayOf(0x0a, 0x7f))
        val scope = testScope()
        try {
            val dataStore = createDataStore(file, scope)
            val store = PreferencesForecastTargetStore(dataStore)

            assertNull(store.target.first())
            assertEquals(emptyMap(), dataStore.data.first().asMap())
        } finally {
            scope.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun withStore(
        block: suspend (DataStore<Preferences>, ForecastTargetStore) -> Unit,
    ) {
        val directory = Files.createTempDirectory("meteoone-target").toFile()
        val file = File(directory, "forecast_target.preferences_pb")
        val scope = testScope()
        try {
            val dataStore = createDataStore(file, scope)
            block(dataStore, PreferencesForecastTargetStore(dataStore))
        } finally {
            scope.close()
            directory.deleteRecursively()
        }
    }

    private fun createDataStore(
        file: File,
        scope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { file },
    )

    private fun testScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun CoroutineScope.close() {
        coroutineContext[Job]?.cancelAndJoin()
    }
}
