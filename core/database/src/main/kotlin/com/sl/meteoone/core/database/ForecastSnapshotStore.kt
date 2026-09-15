package com.sl.meteoone.core.database

import android.content.Context
import androidx.room.Room
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.FusedForecast
import kotlinx.coroutines.flow.Flow

/**
 * Persistent local source for canonical fused forecast snapshots.
 *
 * Callers may address persistence only with the privacy-reduced [ForecastCoordinate]. Raw device
 * coordinates never cross this API.
 */
interface ForecastSnapshotStore {
    fun observe(coordinate: ForecastCoordinate): Flow<FusedForecast?>

    suspend fun read(coordinate: ForecastCoordinate): FusedForecast?

    suspend fun replace(
        coordinate: ForecastCoordinate,
        forecast: FusedForecast,
    )
}

/** Android composition entry point for the M2 Room-backed forecast store. */
object ForecastSnapshotDatabase {
    private const val DATABASE_NAME = "meteoone.db"

    @Volatile
    private var instance: MeteoOneDatabase? = null

    fun open(context: Context): ForecastSnapshotStore =
        RoomForecastSnapshotStore(database(context).forecastSnapshotDao())

    internal fun database(context: Context): MeteoOneDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MeteoOneDatabase::class.java,
                DATABASE_NAME,
            ).build().also { database ->
                instance = database
            }
        }
}

internal class RoomForecastSnapshotStore(
    private val dao: ForecastSnapshotDao,
) : ForecastSnapshotStore {
    override fun observe(coordinate: ForecastCoordinate): Flow<FusedForecast?> =
        dao.observeSnapshot(coordinate.toPersistedKey().encoded)
            .mapSnapshotRows()

    override suspend fun read(coordinate: ForecastCoordinate): FusedForecast? =
        dao.readSnapshot(coordinate.toPersistedKey().encoded)?.toModel()

    override suspend fun replace(
        coordinate: ForecastCoordinate,
        forecast: FusedForecast,
    ) {
        val rows = forecast.toPersistedRows(coordinate)
        dao.replaceSnapshot(rows.snapshot, rows.hourly)
    }
}
