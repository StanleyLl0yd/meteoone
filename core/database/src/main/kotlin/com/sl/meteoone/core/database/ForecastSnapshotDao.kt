package com.sl.meteoone.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class ForecastSnapshotDao {
    @Transaction
    @Query("SELECT * FROM forecast_snapshots WHERE coordinate_key = :coordinateKey LIMIT 1")
    abstract fun observeSnapshot(coordinateKey: String): Flow<ForecastSnapshotWithHourly?>

    @Transaction
    @Query("SELECT * FROM forecast_snapshots WHERE coordinate_key = :coordinateKey LIMIT 1")
    abstract suspend fun readSnapshot(coordinateKey: String): ForecastSnapshotWithHourly?

    @Query("DELETE FROM forecast_snapshots WHERE coordinate_key = :coordinateKey")
    protected abstract suspend fun deleteSnapshot(coordinateKey: String)

    @Insert
    protected abstract suspend fun insertSnapshot(snapshot: ForecastSnapshotEntity)

    @Insert
    protected abstract suspend fun insertHourly(hourly: List<ForecastHourlyEntity>)

    @Transaction
    open suspend fun replaceSnapshot(
        snapshot: ForecastSnapshotEntity,
        hourly: List<ForecastHourlyEntity>,
    ) {
        require(hourly.isNotEmpty()) { "Persisted forecast snapshot must contain hourly rows" }
        require(hourly.all { it.coordinateKey == snapshot.coordinateKey }) {
            "Persisted forecast rows must use the snapshot coordinate key"
        }
        require(hourly.map { it.position } == hourly.indices.toList()) {
            "Persisted forecast rows must use contiguous positions"
        }

        deleteSnapshot(snapshot.coordinateKey)
        insertSnapshot(snapshot)
        insertHourly(hourly)
    }
}
