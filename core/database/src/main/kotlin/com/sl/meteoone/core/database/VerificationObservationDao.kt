package com.sl.meteoone.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
internal abstract class VerificationObservationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertStation(row: VerificationObservationStationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSurface(row: VerificationSurfaceObservationEntity): Long

    @Update
    protected abstract suspend fun updateSurface(row: VerificationSurfaceObservationEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPrecipitation(
        row: VerificationPrecipitationObservationEntity,
    ): Long

    @Query(
        """
        SELECT * FROM verification_observation_stations
        WHERE source_id = :sourceId AND station_id = :stationId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readStation(
        sourceId: String,
        stationId: String,
    ): VerificationObservationStationEntity?

    @Query(
        """
        SELECT * FROM verification_surface_observations
        WHERE source_id = :sourceId
          AND station_id = :stationId
          AND observed_at_epoch_second = :epochSecond
          AND observed_at_nano = :nano
        LIMIT 1
        """,
    )
    protected abstract suspend fun readSurface(
        sourceId: String,
        stationId: String,
        epochSecond: Long,
        nano: Int,
    ): VerificationSurfaceObservationEntity?

    @Query(
        """
        SELECT * FROM verification_precipitation_observations
        WHERE source_id = :sourceId
          AND station_id = :stationId
          AND interval_start_epoch_second = :startEpochSecond
          AND interval_start_nano = :startNano
          AND interval_end_epoch_second = :endEpochSecond
          AND interval_end_nano = :endNano
        LIMIT 1
        """,
    )
    protected abstract suspend fun readPrecipitation(
        sourceId: String,
        stationId: String,
        startEpochSecond: Long,
        startNano: Int,
        endEpochSecond: Long,
        endNano: Int,
    ): VerificationPrecipitationObservationEntity?

    @Query(
        """
        SELECT * FROM verification_surface_observations
        WHERE source_id = :sourceId
          AND station_id = :stationId
          AND (
            observed_at_epoch_second > :fromEpochSecond OR
            (observed_at_epoch_second = :fromEpochSecond AND observed_at_nano >= :fromNano)
          )
        ORDER BY observed_at_epoch_second, observed_at_nano
        """,
    )
    abstract suspend fun readSurfaceSince(
        sourceId: String,
        stationId: String,
        fromEpochSecond: Long,
        fromNano: Int,
    ): List<VerificationSurfaceObservationEntity>

    @Query(
        """
        SELECT * FROM verification_precipitation_observations
        WHERE source_id = :sourceId
          AND station_id = :stationId
          AND (
            interval_end_epoch_second > :fromEpochSecond OR
            (interval_end_epoch_second = :fromEpochSecond AND interval_end_nano >= :fromNano)
          )
        ORDER BY interval_end_epoch_second, interval_end_nano,
                 interval_start_epoch_second, interval_start_nano
        """,
    )
    abstract suspend fun readPrecipitationSince(
        sourceId: String,
        stationId: String,
        fromEpochSecond: Long,
        fromNano: Int,
    ): List<VerificationPrecipitationObservationEntity>

    @Query(
        """
        SELECT * FROM verification_observation_stations
        WHERE source_id = :sourceId AND station_id = :stationId
        LIMIT 1
        """,
    )
    abstract suspend fun readStationForSeries(
        sourceId: String,
        stationId: String,
    ): VerificationObservationStationEntity?

    @Query(
        """
        DELETE FROM verification_surface_observations
        WHERE observed_at_epoch_second < :cutoffEpochSecond
           OR (observed_at_epoch_second = :cutoffEpochSecond AND observed_at_nano < :cutoffNano)
        """,
    )
    protected abstract suspend fun pruneSurfaceBefore(
        cutoffEpochSecond: Long,
        cutoffNano: Int,
    ): Int

    @Query(
        """
        DELETE FROM verification_precipitation_observations
        WHERE interval_end_epoch_second < :cutoffEpochSecond
           OR (interval_end_epoch_second = :cutoffEpochSecond AND interval_end_nano < :cutoffNano)
        """,
    )
    protected abstract suspend fun prunePrecipitationBefore(
        cutoffEpochSecond: Long,
        cutoffNano: Int,
    ): Int

    @Query(
        """
        DELETE FROM verification_observation_stations
        WHERE NOT EXISTS (
            SELECT 1 FROM verification_surface_observations surface
            WHERE surface.source_id = verification_observation_stations.source_id
              AND surface.station_id = verification_observation_stations.station_id
        )
          AND NOT EXISTS (
            SELECT 1 FROM verification_precipitation_observations precipitation
            WHERE precipitation.source_id = verification_observation_stations.source_id
              AND precipitation.station_id = verification_observation_stations.station_id
        )
        """,
    )
    protected abstract suspend fun pruneOrphanStations(): Int

    @Transaction
    open suspend fun archive(
        station: VerificationObservationStationEntity,
        surface: List<VerificationSurfaceObservationEntity>,
        precipitation: List<VerificationPrecipitationObservationEntity>,
        cutoffEpochSecond: Long,
        cutoffNano: Int,
    ): VerificationObservationArchiveCounts {
        val stationInserted = insertStation(station) != -1L
        if (!stationInserted) {
            val existing = requireNotNull(readStation(station.sourceId, station.stationId)) {
                "Verification observation station disappeared during archival"
            }
            check(existing == station) {
                "Verification observation station metadata conflicts with immutable evidence"
            }
        }

        var insertedSurface = 0
        var enrichedSurface = 0
        var existingSurface = 0
        surface.forEach { row ->
            if (insertSurface(row) != -1L) {
                insertedSurface += 1
            } else {
                val existing = requireNotNull(
                    readSurface(
                        row.sourceId,
                        row.stationId,
                        row.observedAtEpochSecond,
                        row.observedAtNano,
                    ),
                ) {
                    "Verification surface observation disappeared during archival"
                }
                val merged = existing.mergeNonConflicting(row)
                if (merged == existing) {
                    existingSurface += 1
                } else {
                    check(updateSurface(merged) == 1) {
                        "Verification surface observation disappeared during enrichment"
                    }
                    enrichedSurface += 1
                }
            }
        }

        var insertedPrecipitation = 0
        var existingPrecipitation = 0
        precipitation.forEach { row ->
            if (insertPrecipitation(row) != -1L) {
                insertedPrecipitation += 1
            } else {
                val existing = requireNotNull(
                    readPrecipitation(
                        row.sourceId,
                        row.stationId,
                        row.intervalStartEpochSecond,
                        row.intervalStartNano,
                        row.intervalEndEpochSecond,
                        row.intervalEndNano,
                    ),
                ) {
                    "Verification precipitation observation disappeared during archival"
                }
                check(existing == row) {
                    "Verification precipitation observation conflicts with immutable evidence"
                }
                existingPrecipitation += 1
            }
        }

        val prunedSurface = pruneSurfaceBefore(cutoffEpochSecond, cutoffNano)
        val prunedPrecipitation = prunePrecipitationBefore(cutoffEpochSecond, cutoffNano)
        val prunedStations = pruneOrphanStations()

        return VerificationObservationArchiveCounts(
            stationInserted = stationInserted,
            insertedSurface = insertedSurface,
            enrichedSurface = enrichedSurface,
            existingSurface = existingSurface,
            insertedPrecipitation = insertedPrecipitation,
            existingPrecipitation = existingPrecipitation,
            prunedSurface = prunedSurface,
            prunedPrecipitation = prunedPrecipitation,
            prunedStations = prunedStations,
        )
    }
}


private fun VerificationSurfaceObservationEntity.mergeNonConflicting(
    incoming: VerificationSurfaceObservationEntity,
): VerificationSurfaceObservationEntity {
    check(
        sourceId == incoming.sourceId &&
            stationId == incoming.stationId &&
            observedAtEpochSecond == incoming.observedAtEpochSecond &&
            observedAtNano == incoming.observedAtNano,
    ) {
        "Verification surface observation identity changed during enrichment"
    }

    fun mergeField(
        stored: Double?,
        candidate: Double?,
        label: String,
    ): Double? = when {
        stored == null -> candidate
        candidate == null || stored == candidate -> stored
        else -> error("Verification surface $label conflicts with immutable evidence")
    }

    return copy(
        temperatureC = mergeField(temperatureC, incoming.temperatureC, "temperature"),
        pressureSeaLevelHpa = mergeField(
            pressureSeaLevelHpa,
            incoming.pressureSeaLevelHpa,
            "sea-level pressure",
        ),
        windSpeedMps = mergeField(windSpeedMps, incoming.windSpeedMps, "wind speed"),
        windDirectionDegrees = mergeField(
            windDirectionDegrees,
            incoming.windDirectionDegrees,
            "wind direction",
        ),
    )
}
