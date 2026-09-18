package com.sl.meteoone.forecast.repository

import android.content.Context
import com.sl.meteoone.core.database.ForecastSnapshotDatabase
import com.sl.meteoone.core.database.ForecastSnapshotStore
import com.sl.meteoone.core.database.StoredForecastSourceIdentity
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.forecast.data.execution.M1ForecastEngine
import com.sl.meteoone.forecast.data.execution.M1ForecastEngineResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val DEFAULT_FRESH_DURATION: Duration = Duration.ofHours(3)

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
        val sourceForecasts: List<SourceForecast>,
        val failedSources: List<ForecastSourceIdentity>,
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
            sourceForecasts = result.sourceForecasts,
            failedSources = result.failedSources.map { identity ->
                ForecastSourceIdentity(identity.provider, identity.modelFamily)
            },
            degraded = result.failedSources.isNotEmpty(),
        )

        is M1ForecastEngineResult.Unavailable -> ForecastRefreshSourceResult.Unavailable
    }
}

internal class ForecastFreshnessPolicy(
    private val freshFor: Duration = DEFAULT_FRESH_DURATION,
) {
    init {
        require(!freshFor.isNegative && !freshFor.isZero) {
            "Forecast freshness duration must be positive"
        }
    }

    fun classify(
        forecast: FusedForecast,
        now: Instant,
    ): ForecastFreshness {
        val horizonEnd = forecast.hourly.last().weather.time
        if (now.isAfter(horizonEnd)) return ForecastFreshness.EXPIRED

        val age = if (now.isBefore(forecast.generatedAt)) {
            Duration.ZERO
        } else {
            Duration.between(forecast.generatedAt, now)
        }
        return if (age < freshFor) {
            ForecastFreshness.FRESH
        } else {
            ForecastFreshness.STALE
        }
    }

    fun nextTransitionDelay(
        forecast: FusedForecast,
        now: Instant,
    ): Duration? {
        val expiryAt = forecast.hourly.last().weather.time.plusNanos(1)
        val transitionAt = when (classify(forecast, now)) {
            ForecastFreshness.FRESH -> minOf(
                forecast.generatedAt.plus(freshFor),
                expiryAt,
            )
            ForecastFreshness.STALE -> expiryAt
            ForecastFreshness.EXPIRED -> return null
        }
        return Duration.between(now, transitionAt).takeIf { !it.isNegative && !it.isZero }
            ?: Duration.ofNanos(1)
    }
}

internal class DefaultForecastRepository(
    private val store: ForecastSnapshotStore,
    private val refreshSource: ForecastRefreshSource,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val freshnessPolicy: ForecastFreshnessPolicy = ForecastFreshnessPolicy(),
    private val waitForFreshnessTransition: suspend (Duration) -> Unit = { duration ->
        delay(duration.toMillis().coerceAtLeast(1L))
    },
    private val refreshMutex: Mutex = Mutex(),
) : ForecastRepository {
    private val freshnessRevision = MutableStateFlow(0L)

    override fun observe(coordinate: ForecastCoordinate): Flow<ForecastCacheState?> =
        combine(
            store.observe(coordinate),
            freshnessRevision,
        ) { stored, _ -> stored }
            .flatMapLatest { stored ->
                if (stored == null) {
                    flowOf(null)
                } else {
                    flow {
                        while (true) {
                            val now = clock.instant()
                            emit(
                                ForecastCacheState(
                                    forecast = stored.forecast,
                                    freshness = freshnessPolicy.classify(stored.forecast, now),
                                    sourceForecasts = stored.sourceForecasts,
                                    failedSources = stored.failedSources.map { identity ->
                                        ForecastSourceIdentity(identity.provider, identity.modelFamily)
                                    },
                                ),
                            )
                            val delayUntilTransition =
                                freshnessPolicy.nextTransitionDelay(stored.forecast, now) ?: break
                            waitForFreshnessTransition(delayUntilTransition)
                        }
                    }
                }
            }
            .distinctUntilChanged()

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
                        store.replace(
                            coordinate = coordinate,
                            forecast = result.forecast,
                            sourceForecasts = result.sourceForecasts,
                            failedSources = result.failedSources.map { identity ->
                                StoredForecastSourceIdentity(
                                    provider = identity.provider,
                                    modelFamily = identity.modelFamily,
                                )
                            },
                        )
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
    } finally {
        freshnessRevision.update { revision -> revision + 1L }
    }
}
