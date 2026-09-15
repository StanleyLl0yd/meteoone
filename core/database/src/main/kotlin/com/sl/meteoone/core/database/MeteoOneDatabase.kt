package com.sl.meteoone.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ForecastSnapshotEntity::class,
        ForecastHourlyEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class MeteoOneDatabase : RoomDatabase() {
    abstract fun forecastSnapshotDao(): ForecastSnapshotDao
}
