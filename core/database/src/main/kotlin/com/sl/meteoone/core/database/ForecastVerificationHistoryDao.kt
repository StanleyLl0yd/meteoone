package com.sl.meteoone.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal abstract class ForecastVerificationHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertRun(run: VerificationForecastRunEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertHourly(row: VerificationForecastHourlyEntity): Long

    @Query(
        """
        SELECT * FROM verification_forecast_runs
        WHERE coordinate_key = :coordinateKey
          AND provider = :provider
          AND model_family = :modelFamily
          AND model_run_epoch_second = :modelRunEpochSecond
          AND model_run_nano = :modelRunNano
        LIMIT 1
        """,
    )
    protected abstract suspend fun readRun(
        coordinateKey: String,
        provider: String,
        modelFamily: String,
        modelRunEpochSecond: Long,
        modelRunNano: Int,
    ): VerificationForecastRunEntity?

    @Query(
        """
        SELECT * FROM verification_forecast_hourly
        WHERE coordinate_key = :coordinateKey
          AND provider = :provider
          AND model_family = :modelFamily
          AND model_run_epoch_second = :modelRunEpochSecond
          AND model_run_nano = :modelRunNano
          AND valid_time_epoch_second = :validTimeEpochSecond
          AND valid_time_nano = :validTimeNano
        LIMIT 1
        """,
    )
    protected abstract suspend fun readHourly(
        coordinateKey: String,
        provider: String,
        modelFamily: String,
        modelRunEpochSecond: Long,
        modelRunNano: Int,
        validTimeEpochSecond: Long,
        validTimeNano: Int,
    ): VerificationForecastHourlyEntity?

    @Query(
        """
        SELECT * FROM verification_forecast_runs
        WHERE coordinate_key = :coordinateKey
          AND (
            model_run_epoch_second > :fromEpochSecond OR
            (model_run_epoch_second = :fromEpochSecond AND model_run_nano >= :fromNano)
          )
        ORDER BY model_run_epoch_second, model_run_nano, model_family, provider
        """,
    )
    abstract suspend fun readRunsSince(
        coordinateKey: String,
        fromEpochSecond: Long,
        fromNano: Int,
    ): List<VerificationForecastRunEntity>

    @Query(
        """
        SELECT * FROM verification_forecast_hourly
        WHERE coordinate_key = :coordinateKey
          AND (
            model_run_epoch_second > :fromEpochSecond OR
            (model_run_epoch_second = :fromEpochSecond AND model_run_nano >= :fromNano)
          )
        ORDER BY model_run_epoch_second, model_run_nano, model_family, provider,
                 valid_time_epoch_second, valid_time_nano
        """,
    )
    abstract suspend fun readHourlySince(
        coordinateKey: String,
        fromEpochSecond: Long,
        fromNano: Int,
    ): List<VerificationForecastHourlyEntity>

    @Query(
        """
        DELETE FROM verification_forecast_runs
        WHERE model_run_epoch_second < :cutoffEpochSecond
           OR (model_run_epoch_second = :cutoffEpochSecond AND model_run_nano < :cutoffNano)
        """,
    )
    protected abstract suspend fun pruneBefore(
        cutoffEpochSecond: Long,
        cutoffNano: Int,
    ): Int

    @Transaction
    open suspend fun archive(
        rows: List<VerificationForecastRows>,
        cutoffEpochSecond: Long,
        cutoffNano: Int,
    ): VerificationArchiveCounts {
        var insertedRuns = 0
        var insertedPoints = 0
        var existingPoints = 0

        rows.forEach { candidate ->
            val run = candidate.run
            val insertedRun = insertRun(run) != -1L
            if (insertedRun) {
                insertedRuns += 1
            } else {
                val existingRun = requireNotNull(
                    readRun(
                        coordinateKey = run.coordinateKey,
                        provider = run.provider,
                        modelFamily = run.modelFamily,
                        modelRunEpochSecond = run.modelRunEpochSecond,
                        modelRunNano = run.modelRunNano,
                    ),
                ) {
                    "Verification forecast run disappeared during archival"
                }
                check(existingRun.sameRunIdentity(run)) {
                    "Verification forecast run identity conflicts with immutable history"
                }
            }

            candidate.hourly.forEach { row ->
                if (insertHourly(row) != -1L) {
                    insertedPoints += 1
                } else {
                    val existing = requireNotNull(
                        readHourly(
                            coordinateKey = row.coordinateKey,
                            provider = row.provider,
                            modelFamily = row.modelFamily,
                            modelRunEpochSecond = row.modelRunEpochSecond,
                            modelRunNano = row.modelRunNano,
                            validTimeEpochSecond = row.validTimeEpochSecond,
                            validTimeNano = row.validTimeNano,
                        ),
                    ) {
                        "Verification forecast point disappeared during archival"
                    }
                    check(existing == row) {
                        "Verification forecast point conflicts with immutable history"
                    }
                    existingPoints += 1
                }
            }
        }

        val prunedRuns = pruneBefore(cutoffEpochSecond, cutoffNano)
        return VerificationArchiveCounts(
            insertedRuns = insertedRuns,
            insertedPoints = insertedPoints,
            existingPoints = existingPoints,
            prunedRuns = prunedRuns,
        )
    }
}

private fun VerificationForecastRunEntity.sameRunIdentity(
    other: VerificationForecastRunEntity,
): Boolean =
    coordinateKey == other.coordinateKey &&
        latitudeTenths == other.latitudeTenths &&
        longitudeTenths == other.longitudeTenths &&
        provider == other.provider &&
        modelFamily == other.modelFamily &&
        modelRunEpochSecond == other.modelRunEpochSecond &&
        modelRunNano == other.modelRunNano
