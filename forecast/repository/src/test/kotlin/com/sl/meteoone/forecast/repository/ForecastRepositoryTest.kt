package com.sl.meteoone.forecast.repository

import com.sl.meteoone.core.database.ForecastSnapshotStore
import com.sl.meteoone.core.database.StoredForecastSnapshot
import com.sl.meteoone.core.database.StoredForecastSourceIdentity
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ForecastRepositoryTest {
    private val coordinate = ForecastCoordinate(latitude = 59.9, longitude = 30.3)
    private val generatedAt = Instant.parse("2026-09-15T20:00:00Z")
    private val clock = Clock.fixed(generatedAt, ZoneOffset.UTC)

    @Test
    fun observesCachedForecastBeforeAnyRefresh() = runBlocking {
        val cached = forecast(temperatureC = 8.0)
        val store = FakeStore(initial = cached)
        val repository = repository(store) { _, _, _, _ ->
            error("refresh source must not run while observing cache")
        }

        val state = requireNotNull(repository.observe(coordinate).first())
        assertEquals(cached, state.forecast)
        assertEquals(ForecastFreshness.FRESH, state.freshness)
        assertFalse(state.shouldRefresh)
        assertEquals(0, store.replaceCount)
    }

    @Test
    fun successfulRefreshPublishesForecastAndEvidenceOnlyThroughStore() = runBlocking {
        val old = forecast(temperatureC = 8.0)
        val fresh = forecast(temperatureC = 12.0)
        val sourceForecast = sourceForecast(ForecastProvider.OPEN_METEO, ModelFamily.ECMWF_IFS, 11.0)
        val failed = ForecastSourceIdentity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON)
        val store = FakeStore(initial = old)
        var observedGeneratedAt: Instant? = null
        val repository = repository(store) { _, _, _, sourceGeneratedAt ->
            observedGeneratedAt = sourceGeneratedAt
            available(
                forecast = fresh,
                sourceForecasts = listOf(sourceForecast),
                failedSources = listOf(failed),
                degraded = true,
            )
        }

        assertEquals(
            ForecastRefreshResult.UpdatedWithDegradation,
            repository.refresh(coordinate, elevationMeters = 12, timeZoneId = "Europe/Moscow"),
        )
        assertEquals(generatedAt, observedGeneratedAt)
        assertEquals(fresh, store.lastReplacement)
        assertEquals(listOf(sourceForecast), store.lastSourceForecasts)
        assertEquals(
            listOf(StoredForecastSourceIdentity(failed.provider, failed.modelFamily)),
            store.lastFailedSources,
        )
        val state = requireNotNull(repository.observe(coordinate).first())
        assertEquals(fresh, state.forecast)
        assertEquals(listOf(sourceForecast), state.sourceForecasts)
        assertEquals(listOf(failed), state.failedSources)
    }

    @Test
    fun degradedSuccessfulRefreshIsReportedSeparately() = runBlocking {
        val fresh = forecast(temperatureC = 12.0)
        val repository = repository(FakeStore()) { _, _, _, _ ->
            available(forecast = fresh, degraded = true)
        }

        assertEquals(
            ForecastRefreshResult.UpdatedWithDegradation,
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC"),
        )
    }

    @Test
    fun unavailableRefreshPreservesCachedForecastAndEvidence() = runBlocking {
        val cached = forecast(temperatureC = 8.0)
        val source = sourceForecast(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS, 7.0)
        val failed = StoredForecastSourceIdentity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS)
        val store = FakeStore(initial = cached, initialSources = listOf(source), initialFailed = listOf(failed))
        val repository = repository(store) { _, _, _, _ -> ForecastRefreshSourceResult.Unavailable }

        assertEquals(
            ForecastRefreshResult.Unavailable,
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC"),
        )
        assertEquals(0, store.replaceCount)
        val state = requireNotNull(repository.observe(coordinate).first())
        assertEquals(cached, state.forecast)
        assertEquals(listOf(source), state.sourceForecasts)
        assertEquals(
            listOf(ForecastSourceIdentity(failed.provider, failed.modelFamily)),
            state.failedSources,
        )
    }

    @Test
    fun sourceFailurePreservesCachedForecast() = runBlocking {
        val cached = forecast(temperatureC = 8.0)
        val store = FakeStore(initial = cached)
        val repository = repository(store) { _, _, _, _ -> error("network execution failed") }

        assertEquals(
            ForecastRefreshResult.Failed,
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC"),
        )
        assertEquals(0, store.replaceCount)
        assertEquals(cached, repository.observe(coordinate).first()?.forecast)
    }

    @Test
    fun failedPersistenceDoesNotPublishNetworkForecastOrEvidence() = runBlocking {
        val cached = forecast(temperatureC = 8.0)
        val cachedSource = sourceForecast(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS, 8.0)
        val fresh = forecast(temperatureC = 12.0)
        val freshSource = sourceForecast(ForecastProvider.OPEN_METEO, ModelFamily.ECMWF_IFS, 12.0)
        val store = FakeStore(
            initial = cached,
            initialSources = listOf(cachedSource),
            failReplace = true,
        )
        val repository = repository(store) { _, _, _, _ ->
            available(forecast = fresh, sourceForecasts = listOf(freshSource))
        }

        assertEquals(
            ForecastRefreshResult.Failed,
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC"),
        )
        val state = requireNotNull(repository.observe(coordinate).first())
        assertEquals(cached, state.forecast)
        assertEquals(listOf(cachedSource), state.sourceForecasts)
    }

    @Test
    fun networkForecastIsNotDirectlyEmittedByRepository() = runBlocking {
        val cached = forecast(temperatureC = 8.0)
        val fresh = forecast(temperatureC = 12.0)
        val store = FakeStore(initial = cached, publishReplacement = false)
        val repository = repository(store) { _, _, _, _ -> available(forecast = fresh) }

        assertEquals(
            ForecastRefreshResult.Updated,
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC"),
        )
        assertEquals(fresh, store.lastReplacement)
        assertEquals(cached, repository.observe(coordinate).first()?.forecast)
    }

    @Test
    fun failedRefreshReevaluatesFreshnessWithoutReplacingCache() = runBlocking {
        val cached = forecast(temperatureC = 8.0, horizonHours = 6)
        val mutableClock = MutableClock(generatedAt)
        val store = FakeStore(initial = cached)
        val repository = repository(store = store, clock = mutableClock) { _, _, _, _ ->
            ForecastRefreshSourceResult.Unavailable
        }
        val firstObserved = CompletableDeferred<Unit>()
        val observed = mutableListOf<ForecastCacheState?>()
        val collector = launch {
            repository.observe(coordinate).collect { state ->
                observed += state
                if (observed.size == 1) firstObserved.complete(Unit)
                if (observed.size == 2) return@collect
            }
        }

        firstObserved.await()
        assertEquals(ForecastFreshness.FRESH, observed.single()?.freshness)
        mutableClock.now = generatedAt.plus(Duration.ofHours(3))
        assertEquals(
            ForecastRefreshResult.Unavailable,
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC"),
        )
        while (observed.size < 2) delay(1)
        collector.cancel()

        assertEquals(
            listOf(ForecastFreshness.FRESH, ForecastFreshness.STALE),
            observed.take(2).map { it?.freshness },
        )
        assertEquals(cached, observed[1]?.forecast)
        assertEquals(0, store.replaceCount)
    }

    @Test
    fun observedCacheAdvancesFreshnessAtTimeBoundariesWithoutRefresh() = runBlocking {
        val cached = forecast(temperatureC = 8.0, horizonHours = 6)
        val mutableClock = MutableClock(generatedAt)
        val waits = Channel<Duration>(capacity = Channel.UNLIMITED)
        val releases = Channel<Unit>(capacity = Channel.UNLIMITED)
        val repository = DefaultForecastRepository(
            store = FakeStore(initial = cached),
            refreshSource = ForecastRefreshSource { _, _, _, _ ->
                error("refresh source must not run while freshness advances")
            },
            clock = mutableClock,
            ioDispatcher = Dispatchers.Unconfined,
            waitForFreshnessTransition = { duration ->
                waits.send(duration)
                releases.receive()
            },
        )
        val observed = mutableListOf<ForecastFreshness>()
        val collector = launch {
            repository.observe(coordinate)
                .take(3)
                .collect { state -> observed += requireNotNull(state).freshness }
        }

        assertEquals(Duration.ofHours(3), waits.receive())
        assertEquals(listOf(ForecastFreshness.FRESH), observed)

        mutableClock.now = generatedAt.plus(Duration.ofHours(3))
        releases.send(Unit)
        assertEquals(Duration.ofHours(3).plusNanos(1), waits.receive())
        assertEquals(
            listOf(ForecastFreshness.FRESH, ForecastFreshness.STALE),
            observed,
        )

        mutableClock.now = generatedAt.plus(Duration.ofHours(6)).plusNanos(1)
        releases.send(Unit)
        collector.join()
        assertEquals(
            listOf(
                ForecastFreshness.FRESH,
                ForecastFreshness.STALE,
                ForecastFreshness.EXPIRED,
            ),
            observed,
        )
    }

    @Test
    fun concurrentRefreshesAreSerialized() = runBlocking {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondCallerStarted = CountDownLatch(1)
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val source = ForecastRefreshSource { _, _, _, _ ->
            val call = calls.incrementAndGet()
            val nowActive = active.incrementAndGet()
            maxActive.updateAndGet { current -> maxOf(current, nowActive) }
            try {
                if (call == 1) {
                    firstEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS)) { "first refresh was not released" }
                }
                available(forecast = forecast(temperatureC = 10.0 + call))
            } finally {
                active.decrementAndGet()
            }
        }
        val repository = DefaultForecastRepository(
            store = FakeStore(),
            refreshSource = source,
            clock = clock,
            ioDispatcher = Dispatchers.Default,
        )

        val first = async(Dispatchers.Default) {
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC")
        }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) {
            secondCallerStarted.countDown()
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC")
        }
        assertTrue(secondCallerStarted.await(5, TimeUnit.SECONDS))
        delay(100)
        assertEquals(1, calls.get(), "second source call entered before first refresh completed")

        releaseFirst.countDown()
        assertEquals(ForecastRefreshResult.Updated, first.await())
        assertEquals(ForecastRefreshResult.Updated, second.await())
        assertEquals(2, calls.get())
        assertEquals(1, maxActive.get())
    }

    @Test
    fun cancellationIsNotConvertedIntoRefreshFailure() {
        val repository = repository(FakeStore()) { _, _, _, _ ->
            throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> {
            runBlocking {
                repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC")
            }
        }
    }

    private fun repository(
        store: ForecastSnapshotStore,
        clock: Clock = this.clock,
        source: ForecastRefreshSource,
    ): ForecastRepository = DefaultForecastRepository(
        store = store,
        refreshSource = source,
        clock = clock,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun available(
        forecast: FusedForecast,
        sourceForecasts: List<SourceForecast> = emptyList(),
        failedSources: List<ForecastSourceIdentity> = emptyList(),
        degraded: Boolean = false,
    ) = ForecastRefreshSourceResult.Available(
        forecast = forecast,
        sourceForecasts = sourceForecasts,
        failedSources = failedSources,
        degraded = degraded,
    )

    private fun forecast(
        temperatureC: Double,
        horizonHours: Long = 1,
    ): FusedForecast = FusedForecast(
        location = location(),
        generatedAt = generatedAt,
        hourly = (1L..horizonHours).map { hour ->
            FusedHourlyForecast(
                weather = hourly(generatedAt.plus(Duration.ofHours(hour)), temperatureC),
                providerCount = 1,
                independentEvidenceCount = 1,
                agreement = ModelAgreement.INSUFFICIENT,
            )
        },
    )

    private fun sourceForecast(
        provider: ForecastProvider,
        modelFamily: ModelFamily,
        temperatureC: Double,
    ): SourceForecast = SourceForecast(
        origin = ForecastOrigin(
            provider = provider,
            modelFamily = modelFamily,
            modelRun = generatedAt.minus(Duration.ofHours(6)),
            generatedAt = generatedAt,
        ),
        location = location(),
        hourly = listOf(hourly(generatedAt.plus(Duration.ofHours(1)), temperatureC)),
    )

    private fun location() = ForecastLocation(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        elevationMeters = 12,
        timeZoneId = "Europe/Moscow",
    )

    private fun hourly(time: Instant, temperatureC: Double) = HourlyWeatherPoint(
        time = time,
        temperatureC = temperatureC,
        feelsLikeC = null,
        dewPointC = null,
        humidityPercent = null,
        pressureSeaLevelHpa = null,
        windSpeedMps = null,
        windGustMps = null,
        windDirectionDegrees = null,
        precipitationMm = null,
        precipitationProbabilityPercent = null,
        cloudCoverPercent = null,
        visibilityMeters = null,
    )
}

class ForecastFreshnessPolicyTest {
    private val generatedAt = Instant.parse("2026-09-15T20:00:00Z")
    private val policy = ForecastFreshnessPolicy()

    @Test
    fun isFreshImmediatelyBeforeThreeHours() {
        assertEquals(
            ForecastFreshness.FRESH,
            policy.classify(forecast(horizonHours = 6), generatedAt.plus(Duration.ofHours(3)).minusNanos(1)),
        )
    }

    @Test
    fun becomesStaleExactlyAtThreeHours() {
        assertEquals(
            ForecastFreshness.STALE,
            policy.classify(forecast(horizonHours = 6), generatedAt.plus(Duration.ofHours(3))),
        )
    }

    @Test
    fun futureGeneratedAtFromClockSkewUsesZeroAge() {
        assertEquals(
            ForecastFreshness.FRESH,
            policy.classify(forecast(horizonHours = 6), generatedAt.minus(Duration.ofHours(2))),
        )
    }

    @Test
    fun horizonEndIsStillUsableAtExactTimestamp() {
        val forecast = forecast(horizonHours = 3)
        assertEquals(
            ForecastFreshness.STALE,
            policy.classify(forecast, forecast.hourly.last().weather.time),
        )
    }

    @Test
    fun becomesExpiredAfterFinalForecastTimestamp() {
        val forecast = forecast(horizonHours = 3)
        assertEquals(
            ForecastFreshness.EXPIRED,
            policy.classify(forecast, forecast.hourly.last().weather.time.plusNanos(1)),
        )
    }

    @Test
    fun staleAndExpiredStatesRequestRefresh() {
        val forecast = forecast(horizonHours = 6)
        assertFalse(ForecastCacheState(forecast, ForecastFreshness.FRESH).shouldRefresh)
        assertTrue(ForecastCacheState(forecast, ForecastFreshness.STALE).shouldRefresh)
        assertTrue(ForecastCacheState(forecast, ForecastFreshness.EXPIRED).shouldRefresh)
    }

    private fun forecast(horizonHours: Long): FusedForecast {
        val coordinate = ForecastCoordinate(59.9, 30.3)
        return FusedForecast(
            location = ForecastLocation(
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
                elevationMeters = null,
                timeZoneId = "UTC",
            ),
            generatedAt = generatedAt,
            hourly = (1L..horizonHours).map { hour ->
                FusedHourlyForecast(
                    weather = HourlyWeatherPoint(
                        time = generatedAt.plus(Duration.ofHours(hour)),
                        temperatureC = 10.0,
                        feelsLikeC = null,
                        dewPointC = null,
                        humidityPercent = null,
                        pressureSeaLevelHpa = null,
                        windSpeedMps = null,
                        windGustMps = null,
                        windDirectionDegrees = null,
                        precipitationMm = null,
                        precipitationProbabilityPercent = null,
                        cloudCoverPercent = null,
                        visibilityMeters = null,
                    ),
                    providerCount = 1,
                    independentEvidenceCount = 1,
                    agreement = ModelAgreement.INSUFFICIENT,
                )
            },
        )
    }
}

private class MutableClock(
    var now: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now
}

private class FakeStore(
    initial: FusedForecast? = null,
    initialSources: List<SourceForecast> = emptyList(),
    initialFailed: List<StoredForecastSourceIdentity> = emptyList(),
    private val failReplace: Boolean = false,
    private val publishReplacement: Boolean = true,
) : ForecastSnapshotStore {
    private val state = MutableStateFlow(
        initial?.let { StoredForecastSnapshot(it, initialSources, initialFailed) },
    )

    var replaceCount: Int = 0
        private set
    var lastReplacement: FusedForecast? = null
        private set
    var lastSourceForecasts: List<SourceForecast> = emptyList()
        private set
    var lastFailedSources: List<StoredForecastSourceIdentity> = emptyList()
        private set

    override fun observe(coordinate: ForecastCoordinate): Flow<StoredForecastSnapshot?> = state

    override suspend fun read(coordinate: ForecastCoordinate): StoredForecastSnapshot? = state.value

    override suspend fun replace(
        coordinate: ForecastCoordinate,
        forecast: FusedForecast,
        sourceForecasts: List<SourceForecast>,
        failedSources: List<StoredForecastSourceIdentity>,
    ) {
        replaceCount += 1
        lastReplacement = forecast
        lastSourceForecasts = sourceForecasts
        lastFailedSources = failedSources
        if (failReplace) error("persistence failed")
        if (publishReplacement) {
            state.value = StoredForecastSnapshot(forecast, sourceForecasts, failedSources)
        }
    }
}
