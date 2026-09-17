package com.sl.meteoone.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ForecastSnapshotEntity::class,
        ForecastHourlyEntity::class,
        ForecastSourceEntity::class,
        ForecastSourceHourlyEntity::class,
        ForecastFailedSourceEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
internal abstract class MeteoOneDatabase : RoomDatabase() {
    abstract fun forecastSnapshotDao(): ForecastSnapshotDao
}

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `forecast_sources` (
                `coordinate_key` TEXT NOT NULL,
                `provider` TEXT NOT NULL,
                `model_family` TEXT NOT NULL,
                `model_run_epoch_second` INTEGER,
                `model_run_nano` INTEGER,
                `generated_at_epoch_second` INTEGER NOT NULL,
                `generated_at_nano` INTEGER NOT NULL,
                PRIMARY KEY(`coordinate_key`, `provider`, `model_family`),
                FOREIGN KEY(`coordinate_key`) REFERENCES `forecast_snapshots`(`coordinate_key`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_forecast_sources_coordinate_key` " +
                "ON `forecast_sources` (`coordinate_key`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `forecast_source_hourly` (
                `coordinate_key` TEXT NOT NULL,
                `provider` TEXT NOT NULL,
                `model_family` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `time_epoch_second` INTEGER NOT NULL,
                `time_nano` INTEGER NOT NULL,
                `temperature_c` REAL,
                `feels_like_c` REAL,
                `dew_point_c` REAL,
                `humidity_percent` REAL,
                `pressure_sea_level_hpa` REAL,
                `wind_speed_mps` REAL,
                `wind_gust_mps` REAL,
                `wind_direction_degrees` REAL,
                `precipitation_mm` REAL,
                `precipitation_probability_percent` REAL,
                `cloud_cover_percent` REAL,
                `visibility_meters` REAL,
                `condition` TEXT NOT NULL,
                `wind_gust_interval_start_epoch_second` INTEGER,
                `wind_gust_interval_start_nano` INTEGER,
                `precipitation_interval_start_epoch_second` INTEGER,
                `precipitation_interval_start_nano` INTEGER,
                PRIMARY KEY(`coordinate_key`, `provider`, `model_family`, `position`),
                FOREIGN KEY(`coordinate_key`, `provider`, `model_family`)
                    REFERENCES `forecast_sources`(`coordinate_key`, `provider`, `model_family`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_forecast_source_hourly_coordinate_key_provider_model_family` " +
                "ON `forecast_source_hourly` (`coordinate_key`, `provider`, `model_family`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `forecast_failed_sources` (
                `coordinate_key` TEXT NOT NULL,
                `provider` TEXT NOT NULL,
                `model_family` TEXT NOT NULL,
                PRIMARY KEY(`coordinate_key`, `provider`, `model_family`),
                FOREIGN KEY(`coordinate_key`) REFERENCES `forecast_snapshots`(`coordinate_key`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_forecast_failed_sources_coordinate_key` " +
                "ON `forecast_failed_sources` (`coordinate_key`)",
        )
    }
}
