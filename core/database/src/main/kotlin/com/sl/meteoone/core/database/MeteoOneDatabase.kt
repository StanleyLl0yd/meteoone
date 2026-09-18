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
        VerificationForecastRunEntity::class,
        VerificationForecastHourlyEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
internal abstract class MeteoOneDatabase : RoomDatabase() {
    abstract fun forecastSnapshotDao(): ForecastSnapshotDao
    abstract fun forecastVerificationHistoryDao(): ForecastVerificationHistoryDao
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


internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `verification_forecast_runs` (
                `coordinate_key` TEXT NOT NULL,
                `latitude_tenths` INTEGER NOT NULL,
                `longitude_tenths` INTEGER NOT NULL,
                `provider` TEXT NOT NULL,
                `model_family` TEXT NOT NULL,
                `model_run_epoch_second` INTEGER NOT NULL,
                `model_run_nano` INTEGER NOT NULL,
                `first_captured_at_epoch_second` INTEGER NOT NULL,
                `first_captured_at_nano` INTEGER NOT NULL,
                `elevation_meters` INTEGER,
                `time_zone_id` TEXT NOT NULL,
                PRIMARY KEY(
                    `coordinate_key`,
                    `provider`,
                    `model_family`,
                    `model_run_epoch_second`,
                    `model_run_nano`
                )
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_verification_forecast_runs_coordinate_key` " +
                "ON `verification_forecast_runs` (`coordinate_key`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_verification_forecast_runs_model_run_epoch_second_model_run_nano` " +
                "ON `verification_forecast_runs` " +
                "(`model_run_epoch_second`, `model_run_nano`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `verification_forecast_hourly` (
                `coordinate_key` TEXT NOT NULL,
                `provider` TEXT NOT NULL,
                `model_family` TEXT NOT NULL,
                `model_run_epoch_second` INTEGER NOT NULL,
                `model_run_nano` INTEGER NOT NULL,
                `valid_time_epoch_second` INTEGER NOT NULL,
                `valid_time_nano` INTEGER NOT NULL,
                `lead_seconds` INTEGER NOT NULL,
                `lead_nano` INTEGER NOT NULL,
                `temperature_c` REAL,
                `pressure_sea_level_hpa` REAL,
                `wind_speed_mps` REAL,
                `wind_direction_degrees` REAL,
                `precipitation_mm` REAL,
                `precipitation_interval_start_epoch_second` INTEGER,
                `precipitation_interval_start_nano` INTEGER,
                PRIMARY KEY(
                    `coordinate_key`,
                    `provider`,
                    `model_family`,
                    `model_run_epoch_second`,
                    `model_run_nano`,
                    `valid_time_epoch_second`,
                    `valid_time_nano`
                ),
                FOREIGN KEY(
                    `coordinate_key`,
                    `provider`,
                    `model_family`,
                    `model_run_epoch_second`,
                    `model_run_nano`
                ) REFERENCES `verification_forecast_runs`(
                    `coordinate_key`,
                    `provider`,
                    `model_family`,
                    `model_run_epoch_second`,
                    `model_run_nano`
                ) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_verification_forecast_hourly_coordinate_key_provider_model_family_" +
                "model_run_epoch_second_model_run_nano` " +
                "ON `verification_forecast_hourly` " +
                "(`coordinate_key`, `provider`, `model_family`, " +
                "`model_run_epoch_second`, `model_run_nano`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_verification_forecast_hourly_valid_time_epoch_second_valid_time_nano` " +
                "ON `verification_forecast_hourly` " +
                "(`valid_time_epoch_second`, `valid_time_nano`)",
        )
    }
}
