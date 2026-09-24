package com.sl.meteoone.backend.gateway

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastTarget
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.fail

class ForecastGatewayCacheTest {
    @Test
    fun cacheKeyUsesPrivacySafeTargetAndCeilingForecastHour() {
        val target = target()

        assertEquals(
            Instant.parse("2026-09-24T10:00:00Z"),
            ForecastGatewayCacheKey.from(
                target = target,
                requestedAt = Instant.parse("2026-09-24T10:00:00Z"),
            ).forecastStartHour,
        )
        assertEquals(
            Instant.parse("2026-09-24T12:00:00Z"),
            ForecastGatewayCacheKey.from(
                target = target,
                requestedAt = Instant.parse("2026-09-24T11:00:00.001Z"),
            ).forecastStartHour,
        )
    }

    @Test
    fun cacheKeyDoesNotAliasDifferentTargetMetadata() {
        val requestedAt = Instant.parse("2026-09-24T10:15:00Z")
        val baseline = ForecastGatewayCacheKey.from(target(), requestedAt)

        assertNotEquals(
            baseline,
            ForecastGatewayCacheKey.from(
                target().copy(elevationMeters = 101),
                requestedAt,
            ),
        )
        assertNotEquals(
            baseline,
            ForecastGatewayCacheKey.from(
                target().copy(timeZoneId = "Europe/Amsterdam"),
                requestedAt,
            ),
        )
        assertNotEquals(
            baseline,
            ForecastGatewayCacheKey.from(
                target().copy(coordinate = ForecastCoordinate(59.8, 30.3)),
                requestedAt,
            ),
        )
    }

    @Test
    fun concurrentEquivalentLoadsAreSingleFlight() = runBlocking {
        val cache = cache()
        val key = key()
        val loadCount = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            cache.getOrLoad(key) {
                loadCount.incrementAndGet()
                entered.complete(Unit)
                release.await()
                "forecast"
            }
        }

        entered.await()

        val second = async {
            cache.getOrLoad(key) {
                loadCount.incrementAndGet()
                "unexpected"
            }
        }

        release.complete(Unit)

        assertEquals("forecast", first.await())
        assertEquals("forecast", second.await())
        assertEquals(1, loadCount.get())
    }

    @Test
    fun successfulValueIsCachedUntilTtlExpires() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-24T10:00:00Z"))
        val cache = cache(clock = clock, ttl = Duration.ofMinutes(10))
        val key = key()
        var loads = 0

        assertEquals("value-1", cache.getOrLoad(key) { "value-" + (++loads) })
        assertEquals("value-1", cache.getOrLoad(key) { "value-" + (++loads) })

        clock.advance(Duration.ofMinutes(10))

        assertEquals("value-2", cache.getOrLoad(key) { "value-" + (++loads) })
        assertEquals(2, loads)
    }

    @Test
    fun failedLoadDoesNotPoisonCache() = runBlocking {
        val cache = cache()
        val key = key()

        try {
            cache.getOrLoad(key) {
                throw IllegalStateException("provider failed")
            }
            fail("Expected provider failure")
        } catch (error: IllegalStateException) {
            assertEquals("provider failed", error.message)
        }

        assertEquals("recovered", cache.getOrLoad(key) { "recovered" })
    }

    @Test
    fun cancelledLoadDoesNotPoisonCache() = runBlocking {
        val cache = cache()
        val key = key()

        try {
            cache.getOrLoad(key) {
                throw CancellationException("cancelled")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertEquals("recovered", cache.getOrLoad(key) { "recovered" })
    }

    @Test
    fun cacheSizeIsBoundedAndEvictsEarliestExpiry() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-24T10:00:00Z"))
        val cache = cache(clock = clock, maxEntries = 2)
        val first = key(latitude = 59.9)
        val second = key(latitude = 60.0)
        val third = key(latitude = 60.1)

        assertEquals("first", cache.getOrLoad(first) { "first" })
        clock.advance(Duration.ofSeconds(1))
        assertEquals("second", cache.getOrLoad(second) { "second" })
        clock.advance(Duration.ofSeconds(1))
        assertEquals("third", cache.getOrLoad(third) { "third" })

        var reloads = 0
        assertEquals(
            "first-reloaded",
            cache.getOrLoad(first) {
                reloads += 1
                "first-reloaded"
            },
        )
        assertEquals(1, reloads)
    }

    private fun cache(
        clock: Clock = Clock.systemUTC(),
        ttl: Duration = Duration.ofMinutes(15),
        maxEntries: Int = 32,
    ): SingleFlightForecastGatewayCache<String> =
        SingleFlightForecastGatewayCache(
            ttl = ttl,
            maxEntries = maxEntries,
            clock = clock,
        )

    private fun key(latitude: Double = 59.9): ForecastGatewayCacheKey =
        ForecastGatewayCacheKey.from(
            target = target(latitude),
            requestedAt = Instant.parse("2026-09-24T10:15:00Z"),
        )

    private fun target(latitude: Double = 59.9): ForecastTarget =
        ForecastTarget(
            coordinate = ForecastCoordinate(latitude, 30.3),
            elevationMeters = 100,
            timeZoneId = "UTC",
        )

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock =
            if (zone == ZoneOffset.UTC) this else Clock.fixed(current, zone)

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
