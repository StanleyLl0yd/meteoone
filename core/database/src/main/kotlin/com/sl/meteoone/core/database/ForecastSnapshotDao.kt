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
    abstract fun observeSnapshot(coordinateKey: String): Flow<ForecastSnapshotWithEvidence?>

    @Transaction
    @Query("SELECT * FROM forecast_snapshots WHERE coordinate_key = :coordinateKey LIMIT 1")
    abstract suspend fun readSnapshot(coordinateKey: String): ForecastSnapshotWithEvidence?

    @Query("DELETE FROM forecast_snapshots WHERE coordinate_key = :coordinateKey")
    protected abstract suspend fun deleteSnapshot(coordinateKey: String)

    @Insert
    protected abstract suspend fun insertSnapshot(snapshot: ForecastSnapshotEntity)

    @Insert
    protected abstract suspend fun insertHourly(hourly: List<ForecastHourlyEntity>)

    @Insert
    protected abstract suspend fun insertSources(sources: List<ForecastSourceEntity>)

    @Insert
    protected abstract suspend fun insertSourceHourly(hourly: List<ForecastSourceHourlyEntity>)

    @Insert
    protected abstract suspend fun insertFailedSources(failedSources: List<ForecastFailedSourceEntity>)

    @Transaction
    open suspend fun replaceSnapshot(
        snapshot: ForecastSnapshotEntity,
        hourly: List<ForecastHourlyEntity>,
        sources: List<ForecastSourceEntity>,
        sourceHourly: List<ForecastSourceHourlyEntity>,
        failedSources: List<ForecastFailedSourceEntity>,
    ) {
        require(hourly.isNotEmpty()) { "Persisted forecast snapshot must contain hourly rows" }
        require(hourly.all { it.coordinateKey == snapshot.coordinateKey }) {
            "Persisted forecast rows must use the snapshot coordinate key"
        }
        require(hourly.map { it.position } == hourly.indices.toList()) {
            "Persisted forecast rows must use contiguous positions"
        }

        val successfulIdentities = sources.map { it.provider to it.modelFamily }
        require(successfulIdentities.size == successfulIdentities.toSet().size) {
            "Persisted successful forecast source identities must be unique"
        }
        require(sources.all { it.coordinateKey == snapshot.coordinateKey }) {
            "Persisted source metadata must use the snapshot coordinate key"
        }

        val hourlyByIdentity = sourceHourly.groupBy { it.provider to it.modelFamily }
        require(sourceHourly.all { it.coordinateKey == snapshot.coordinateKey }) {
            "Persisted source hourly rows must use the snapshot coordinate key"
        }
        require(hourlyByIdentity.keys == successfulIdentities.toSet()) {
            "Persisted source hourly rows must match successful source metadata exactly"
        }
        hourlyByIdentity.forEach { (_, rows) ->
            require(rows.sortedBy { it.position }.map { it.position } == rows.indices.toList()) {
                "Persisted source hourly rows must use contiguous positions per source"
            }
        }

        val failedIdentities = failedSources.map { it.provider to it.modelFamily }
        require(failedIdentities.size == failedIdentities.toSet().size) {
            "Persisted failed forecast source identities must be unique"
        }
        require(failedSources.all { it.coordinateKey == snapshot.coordinateKey }) {
            "Persisted failed source identities must use the snapshot coordinate key"
        }
        require(successfulIdentities.toSet().intersect(failedIdentities.toSet()).isEmpty()) {
            "Persisted forecast source identity cannot be both successful and failed"
        }

        deleteSnapshot(snapshot.coordinateKey)
        insertSnapshot(snapshot)
        insertHourly(hourly)
        if (sources.isNotEmpty()) insertSources(sources)
        if (sourceHourly.isNotEmpty()) insertSourceHourly(sourceHourly)
        if (failedSources.isNotEmpty()) insertFailedSources(failedSources)
    }
}
