package com.sl.meteoone.core.database

import android.content.Context
import androidx.room.Room
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import kotlinx.coroutines.flow.Flow

data class StoredForecastSourceIdentity(
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
)

data class StoredForecastSnapshot(
    val forecast: FusedForecast,
    val sourceForecasts: List<SourceForecast>,
    val failedSources: List<StoredForecastSourceIdentity>,
)

/**
 * Persistent local source for canonical fused forecast snapshots and their model-comparison evidence.
 *
 * Callers may address persistence only with the privacy-reduced [ForecastCoordinate]. Raw device
 * coordinates never cross this API.
 */
interface ForecastSnapshotStore {
    fun observe(coordinate: ForecastCoordinate): Flow<StoredForecastSnapshot?>

    suspend fun read(coordinate: ForecastCoordinate): StoredForecastSnapshot?

    suspend fun replace(
        coordinate: ForecastCoordinate,
        forecast: FusedForecast,
        sourceForecasts: List<SourceForecast> = emptyList(),
        failedSources: List<StoredForecastSourceIdentity> = emptyList(),
    )
}

/** Android composition entry point for the Room-backed forecast store. */
object ForecastSnapshotDatabase {
    private const val DATABASE_NAME = "meteoone.db"

    @Volatile
    private var instance: MeteoOneDatabase? = null

    fun open(context: Context): ForecastSnapshotStore =
        RoomForecastSnapshotStore(database(context).forecastSnapshotDao())

    fun openVerificationHistory(context: Context): ForecastVerificationHistoryStore =
        RoomForecastVerificationHistoryStore(database(context).forecastVerificationHistoryDao())

    fun openVerificationObservations(context: Context): VerificationObservationStore =
        RoomVerificationObservationStore(database(context).verificationObservationDao())

    internal fun database(context: Context): MeteoOneDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MeteoOneDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { database ->
                    instance = database
                }
        }
}

internal class RoomForecastSnapshotStore(
    private val dao: ForecastSnapshotDao,
) : ForecastSnapshotStore {
    override fun observe(coordinate: ForecastCoordinate): Flow<StoredForecastSnapshot?> =
        dao.observeSnapshot(coordinate.toPersistedKey().encoded)
            .mapSnapshotRows()

    override suspend fun read(coordinate: ForecastCoordinate): StoredForecastSnapshot? =
        dao.readSnapshot(coordinate.toPersistedKey().encoded)?.toModel()

    override suspend fun replace(
        coordinate: ForecastCoordinate,
        forecast: FusedForecast,
        sourceForecasts: List<SourceForecast>,
        failedSources: List<StoredForecastSourceIdentity>,
    ) {
        val rows = forecast.toPersistedRows(
            coordinate = coordinate,
            sourceForecasts = sourceForecasts,
            failedSources = failedSources,
        )
        dao.replaceSnapshot(
            snapshot = rows.snapshot,
            hourly = rows.hourly,
            sources = rows.sources,
            sourceHourly = rows.sourceHourly,
            failedSources = rows.failedSources,
        )
    }
}
