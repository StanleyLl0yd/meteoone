package com.sl.meteoone.backend.provider

import com.sl.meteoone.core.model.ForecastProvider
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderHealthTrackerTest {
    @Test
    fun healthFailuresDegradeThenOpenAtThreshold() {
        val now = AtomicLong(0L)
        val tracker = tracker(now, threshold = 2)

        val first = assertNotNull(tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"))
        tracker.recordHealthFailure(first)
        assertEquals(
            ProviderHealthSnapshot(
                state = ProviderHealthState.DEGRADED,
                consecutiveFailures = 1,
                retryAfter = null,
            ),
            tracker.snapshot(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )

        val second = assertNotNull(tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"))
        tracker.recordHealthFailure(second)

        val opened = tracker.snapshot(ForecastProvider.NOAA_NOMADS, "weather.example")
        assertEquals(ProviderHealthState.OPEN, opened.state)
        assertEquals(2, opened.consecutiveFailures)
        assertEquals(Duration.ofSeconds(30), opened.retryAfter)
        assertNull(tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"))
    }

    @Test
    fun cooldownAdmitsExactlyOneHalfOpenProbe() {
        val now = AtomicLong(0L)
        val tracker = tracker(now, threshold = 1)
        val permit = assertNotNull(
            tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )
        tracker.recordHealthFailure(permit)

        now.set(Duration.ofSeconds(30).toNanos())

        val probe = assertNotNull(
            tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )
        assertTrue(probe.probe)
        assertEquals(
            ProviderHealthState.HALF_OPEN,
            tracker.snapshot(ForecastProvider.NOAA_NOMADS, "weather.example").state,
        )
        assertNull(tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"))

        tracker.recordSuccess(probe)
        assertEquals(
            ProviderHealthState.HEALTHY,
            tracker.snapshot(ForecastProvider.NOAA_NOMADS, "weather.example").state,
        )
        assertFalse(
            assertNotNull(
                tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
            ).probe,
        )
    }

    @Test
    fun neutralProbeCanBeRetriedImmediately() {
        val now = AtomicLong(0L)
        val tracker = tracker(now, threshold = 1)
        tracker.recordHealthFailure(
            assertNotNull(tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example")),
        )
        now.set(Duration.ofSeconds(30).toNanos())

        val firstProbe = assertNotNull(
            tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )
        tracker.recordNeutral(firstProbe)

        val replacementProbe = assertNotNull(
            tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )
        assertTrue(replacementProbe.probe)
    }

    @Test
    fun failedProbeReopensForAnotherBoundedCooldown() {
        val now = AtomicLong(0L)
        val tracker = tracker(now, threshold = 1)
        tracker.recordHealthFailure(
            assertNotNull(tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example")),
        )
        now.set(Duration.ofSeconds(30).toNanos())

        val probe = assertNotNull(
            tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )
        tracker.recordHealthFailure(probe)

        val snapshot = tracker.snapshot(ForecastProvider.NOAA_NOMADS, "weather.example")
        assertEquals(ProviderHealthState.OPEN, snapshot.state)
        assertEquals(Duration.ofSeconds(30), snapshot.retryAfter)
    }

    @Test
    fun providerAndHostHealthAreIsolated() {
        val now = AtomicLong(0L)
        val tracker = tracker(now, threshold = 1)
        tracker.recordHealthFailure(
            assertNotNull(tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example")),
        )

        assertNotNull(tracker.tryAcquire(ForecastProvider.ECMWF_OPEN_DATA, "weather.example"))
        assertNotNull(tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "other.example"))
        assertNull(tracker.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"))
    }

    private fun tracker(
        now: AtomicLong,
        threshold: Int,
    ): ProviderHealthTracker =
        ProviderHealthTracker(
            failureThreshold = threshold,
            cooldown = Duration.ofSeconds(30),
            monotonicNanos = now::get,
        )
}
