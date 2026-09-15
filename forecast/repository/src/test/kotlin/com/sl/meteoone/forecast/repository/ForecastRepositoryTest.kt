package com.sl.meteoone.forecast.repository

import com.sl.meteoone.core.database.ForecastSnapshotStore
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ForecastRepositoryTest {
    private val coordinate = ForecastCoordinate(latitude = 59.9, longitude = 30.3)
    private val generatedAt = Instant.parse("2026-09-15T20:00:00Z")
    private val clock = Clock.fixed(generatedAt, ZoneOffset.UTC)

    @Test
    fun observesCachedForecastBeforeAnyRefresh() = runBlocking {
        val cached = forecast(temperatureC = 8.0)
        val store = FakeStore(initial = cached)
        val repository = repository(store) {
            error("refresh source must not run while observing cache")
        }

        assertEquals(cached, repository.observe(coordinate).first())
        assertEquals(0, store.replaceCount)
    }

    @Test
    fun successfulRefreshPublishesOnlyThroughStore() = runBlocking {
        val old = forecast(temperatureC = 8.0)
        val fresh = forecast(temperatureC = 12.0)
        val store = FakeStore(initial = old)
        var observedGeneratedAt: Instant? = null
        val repository = repository(store) { _, _, _, sourceGeneratedAt ->
            observedGeneratedAt = sourceGeneratedAt
            ForecastRefreshSourceResult.Available(forecast = fresh, degraded = false)
        }

        assertEquals(
            ForecastRefreshResult.Updated,
            repository.refresh(coordinate, elevationMeters = 12, timeZoneId = "Europe/Moscow"),
        )
        assertEquals(generatedAt, observedGeneratedAt)
        assertEquals(fresh, store.lastReplacement)
        assertEquals(fresh, repository.observe(coordinate).first())
    }

    @Test
    fun degradedSuccessfulRefreshIsReportedSeparately() = runBlocking {
        val fresh = forecast(temperatureC = 12.0)
        val repository = repository(FakeStore()) { _, _, _, _ ->
            ForecastRefreshSourceResult.Available(forecast = fresh, degraded = true)
        }

        assertEquals(
            ForecastRefreshResult.UpdatedWithDegradation,
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC"),
        )
    }

    @Test
    fun unavailableRefreshPreservesCachedForecast() = runBlocking {
        val cached = forecast(temperatureC = 8.0)
        val store = FakeStore(initial = cached)
        val repository = repository(store) { _, _, _, _ ->
            ForecastRefreshSourceResult.Unavailable
        }

        assertEquals(
            ForecastRefreshResult.Unavailable,
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC"),
        )
        assertEquals(0, store.replaceCount)
        assertEquals(cached, repository.observe(coordinate).first())
    }

    @Test
    fun sourceFailurePreservesCachedForecast() = runBlocking {
        val cached = forecast(temperatureC = 8.0)
        val store = FakeStore(initial = cached)
        val repository = repository(store) { _, _, _, _ ->
            error("network execution failed")
        }

        assertEquals(
            ForecastRefreshResult.Failed,
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC"),
        )
        assertEquals(0, store.replaceCount)
        assertEquals(cached, repository.observe(coordinate).first())
    }

    @Test
    fun failedPersistenceDoesNotPublishNetworkForecast() = runBlocking {
        val cached = forecast(temperatureC = 8.0)
        val fresh = forecast(temperatureC = 12.0)
        val store = FakeStore(initial = cached, failReplace = true)
        val repository = repository(store) { _, _, _, _ ->
            ForecastRefreshSourceResult.Available(forecast = fresh, degraded = false)
        }

        assertEquals(
            ForecastRefreshResult.Failed,
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC"),
        )
        assertEquals(cached, repository.observe(coordinate).first())
    }

    @Test
    fun networkForecastIsNotDirectlyEmittedByRepository() = runBlocking {
        val cached = forecast(temperatureC = 8.0)
        val fresh = forecast(temperatureC = 12.0)
        val store = FakeStore(initial = cached, publishReplacement = false)
        val repository = repository(store) { _, _, _, _ ->
            ForecastRefreshSourceResult.Available(forecast = fresh, degraded = false)
        }

        assertEquals(
            ForecastRefreshResult.Updated,
            repository.refresh(coordinate, elevationMeters = null, timeZoneId = "UTC"),
        )
        assertEquals(fresh, store.lastReplacement)
        assertEquals(cached, repository.observe(coordinate).first())
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
                ForecastRefreshSourceResult.Available(
                    forecast = forecast(temperatureC = 10.0 + call),
                    degraded = false,
                )
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
        source: ForecastRefreshSource,
    ): ForecastRepository = DefaultForecastRepository(
        store = store,
        refreshSource = source,
        clock = clock,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun forecast(temperatureC: Double): FusedForecast = FusedForecast(
        location = ForecastLocation(
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            elevationMeters = 12,
            timeZoneId = "Europe/Moscow",
        ),
        generatedAt = generatedAt,
        hourly = listOf(
            FusedHourlyForecast(
                weather = HourlyWeatherPoint(
                    time = generatedAt.plusSeconds(3600),
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
                ),
                providerCount = 1,
                independentEvidenceCount = 1,
                agreement = ModelAgreement.INSUFFICIENT,
            ),
        ),
    )
}

private class FakeStore(
    initial: FusedForecast? = null,
    private val failReplace: Boolean = false,
    private val publishReplacement: Boolean = true,
) : ForecastSnapshotStore {
    private val state = MutableStateFlow(initial)

    var replaceCount: Int = 0
        private set

    var lastReplacement: FusedForecast? = null
        private set

    override fun observe(coordinate: ForecastCoordinate): Flow<FusedForecast?> = state

    override suspend fun read(coordinate: ForecastCoordinate): FusedForecast? = state.value

    override suspend fun replace(
        coordinate: ForecastCoordinate,
        forecast: FusedForecast,
    ) {
        replaceCount += 1
        lastReplacement = forecast
        if (failReplace) error("persistence failed")
        if (publishReplacement) state.value = forecast
    }
}
