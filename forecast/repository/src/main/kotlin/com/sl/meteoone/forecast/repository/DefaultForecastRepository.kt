package com.sl.meteoone.forecast.repository

import android.content.Context
import com.sl.meteoone.core.database.ForecastSnapshotDatabase
import com.sl.meteoone.core.database.ForecastSnapshotStore
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.forecast.data.execution.M1ForecastEngine
import com.sl.meteoone.forecast.data.execution.M1ForecastEngineResult
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun createAndroidForecastRepository(context: Context): ForecastRepository =
    DefaultForecastRepository(
        store = ForecastSnapshotDatabase.open(context),
        refreshSource = M1ForecastRefreshSource(
            engine = M1ForecastEngine.android(context),
        ),
    )

internal sealed interface ForecastRefreshSourceResult {
    data class Available(
        val forecast: FusedForecast,
        val degraded: Boolean,
    ) : ForecastRefreshSourceResult

    data object Unavailable : ForecastRefreshSourceResult
}

internal fun interface ForecastRefreshSource {
    fun forecast(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        timeZoneId: String,
        generatedAt: Instant,
    ): ForecastRefreshSourceResult
}

internal class M1ForecastRefreshSource(
    private val engine: M1ForecastEngine,
) : ForecastRefreshSource {
    override fun forecast(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        timeZoneId: String,
        generatedAt: Instant,
    ): ForecastRefreshSourceResult = when (
        val result = engine.forecast(
            coordinate = coordinate,
            elevationMeters = elevationMeters,
            timeZoneId = timeZoneId,
            generatedAt = generatedAt,
        )
    ) {
        is M1ForecastEngineResult.Available -> ForecastRefreshSourceResult.Available(
            forecast = result.forecast,
            degraded = result.failedSources.isNotEmpty(),
        )

        is M1ForecastEngineResult.Unavailable -> ForecastRefreshSourceResult.Unavailable
    }
}

internal class DefaultForecastRepository(
    private val store: ForecastSnapshotStore,
    private val refreshSource: ForecastRefreshSource,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val refreshMutex: Mutex = Mutex(),
) : ForecastRepository {
    override fun observe(coordinate: ForecastCoordinate): Flow<FusedForecast?> =
        store.observe(coordinate)

    override suspend fun refresh(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        timeZoneId: String,
    ): ForecastRefreshResult = try {
        refreshMutex.withLock {
            withContext(ioDispatcher) {
                when (
                    val result = refreshSource.forecast(
                        coordinate = coordinate,
                        elevationMeters = elevationMeters,
                        timeZoneId = timeZoneId,
                        generatedAt = clock.instant(),
                    )
                ) {
                    is ForecastRefreshSourceResult.Available -> {
                        store.replace(coordinate, result.forecast)
                        if (result.degraded) {
                            ForecastRefreshResult.UpdatedWithDegradation
                        } else {
                            ForecastRefreshResult.Updated
                        }
                    }

                    ForecastRefreshSourceResult.Unavailable -> ForecastRefreshResult.Unavailable
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ForecastRefreshResult.Failed
    } catch (_: LinkageError) {
        ForecastRefreshResult.Failed
    }
}
