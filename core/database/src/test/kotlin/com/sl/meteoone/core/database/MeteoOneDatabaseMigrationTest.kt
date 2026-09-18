package com.sl.meteoone.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.sl.meteoone.core.model.ForecastCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeteoOneDatabaseMigrationTest {
    private val databaseName = "meteoone-v1-v2-migration-test.db"

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MeteoOneDatabase::class.java,
    )

    @Test
    fun v1FusedSnapshotSurvivesWithEmptyComparisonEvidence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(databaseName)

        migrationHelper.createDatabase(databaseName, 1).use { database ->
            database.execSQL(
                """
                INSERT INTO forecast_snapshots (
                    coordinate_key,
                    latitude_tenths,
                    longitude_tenths,
                    generated_at_epoch_second,
                    generated_at_nano,
                    elevation_meters,
                    time_zone_id
                ) VALUES ('599:303', 599, 303, 1000, 123456789, 12, 'UTC')
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO forecast_hourly (
                    coordinate_key,
                    position,
                    time_epoch_second,
                    time_nano,
                    temperature_c,
                    condition,
                    provider_count,
                    independent_evidence_count,
                    agreement
                ) VALUES ('599:303', 0, 4600, 987654321, 7.5, 'CLEAR', 1, 1, 'INSUFFICIENT')
                """.trimIndent(),
            )
        }

        val latest = Room.databaseBuilder(context, MeteoOneDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val stored = assertNotNull(
                RoomForecastSnapshotStore(latest.forecastSnapshotDao()).read(
                    ForecastCoordinate(59.9, 30.3),
                ),
            )
            assertEquals(7.5, stored.forecast.hourly.single().weather.temperatureC)
            assertEquals(emptyList(), stored.sourceForecasts)
            assertEquals(emptyList(), stored.failedSources)

            val sqlite = latest.openHelper.readableDatabase
            assertEquals(1L, sqlite.count("forecast_snapshots"))
            assertEquals(1L, sqlite.count("forecast_hourly"))
            assertEquals(0L, sqlite.count("forecast_sources"))
            assertEquals(0L, sqlite.count("forecast_source_hourly"))
            assertEquals(0L, sqlite.count("forecast_failed_sources"))
        } finally {
            latest.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(table: String): Long =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
}
