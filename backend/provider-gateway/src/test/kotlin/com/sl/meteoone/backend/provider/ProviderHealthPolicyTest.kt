package com.sl.meteoone.backend.provider

import com.sl.meteoone.core.model.ForecastProvider
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderHealthPolicyTest {
    @Test
    fun consecutiveHealthFailuresDegradeThenOpenAndSingleProbeRecovers() = runBlocking {
        val now = AtomicLong(0L)
        val policy = policy(
            now = now,
            threshold = 3,
            cooldown = Duration.ofSeconds(10),
        )

        repeat(2) {
            val permit = assertNotNull(
                policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "Weather.Example"),
            )
            policy.recordFailure(permit, ProviderGatewayFailureReason.IO)
        }

        assertEquals(
            ProviderHealthSnapshot(
                state = ProviderHealthState.DEGRADED,
                consecutiveFailures = 2,
                remainingCooldown = Duration.ZERO,
            ),
            policy.snapshot(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )

        val third = assertNotNull(
            policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )
        policy.recordFailure(third, ProviderGatewayFailureReason.INVALID_RESPONSE)

        assertEquals(ProviderHealthState.OPEN, policy.snapshot(
            ForecastProvider.NOAA_NOMADS,
            "weather.example",
        ).state)
        assertNull(policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"))

        now.addAndGet(Duration.ofSeconds(10).toNanos())

        val probe = assertNotNull(
            policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )
        assertTrue(probe.halfOpenProbe)
        assertNull(policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"))

        policy.recordSuccess(probe)

        assertEquals(
            ProviderHealthSnapshot(
                state = ProviderHealthState.HEALTHY,
                consecutiveFailures = 0,
                remainingCooldown = Duration.ZERO,
            ),
            policy.snapshot(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )
    }

    @Test
    fun cancellationDoesNotConsumeHalfOpenProbeOrIncrementFailures() = runBlocking {
        val now = AtomicLong(0L)
        val cooldown = Duration.ofSeconds(5)
        val policy = policy(
            now = now,
            threshold = 2,
            cooldown = cooldown,
        )

        repeat(2) {
            val permit = assertNotNull(
                policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
            )
            policy.recordFailure(permit, ProviderGatewayFailureReason.IO)
        }

        now.addAndGet(cooldown.toNanos())

        val probe = assertNotNull(
            policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )
        policy.recordFailure(probe, ProviderGatewayFailureReason.CANCELLED)

        val snapshot = policy.snapshot(
            ForecastProvider.NOAA_NOMADS,
            "weather.example",
        )
        assertEquals(ProviderHealthState.OPEN, snapshot.state)
        assertEquals(2, snapshot.consecutiveFailures)
        assertEquals(Duration.ZERO, snapshot.remainingCooldown)

        val retryProbe = assertNotNull(
            policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )
        assertTrue(retryProbe.halfOpenProbe)
    }

    @Test
    fun failedHalfOpenProbeReopensForFullCooldown() = runBlocking {
        val now = AtomicLong(0L)
        val cooldown = Duration.ofSeconds(5)
        val policy = policy(
            now = now,
            threshold = 2,
            cooldown = cooldown,
        )

        repeat(2) {
            val permit = assertNotNull(
                policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
            )
            policy.recordFailure(permit, ProviderGatewayFailureReason.RESPONSE_TOO_LARGE)
        }

        now.addAndGet(cooldown.toNanos())
        val probe = assertNotNull(
            policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )
        policy.recordFailure(probe, ProviderGatewayFailureReason.IO)

        val snapshot = policy.snapshot(
            ForecastProvider.NOAA_NOMADS,
            "weather.example",
        )
        assertEquals(ProviderHealthState.OPEN, snapshot.state)
        assertEquals(cooldown, snapshot.remainingCooldown)
        assertNull(policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"))
    }

    @Test
    fun nonHealthFailuresDoNotDegradeAndProviderHostScopesAreIndependent() = runBlocking {
        val now = AtomicLong(0L)
        val policy = policy(now = now)

        val neutral = assertNotNull(
            policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
        )
        policy.recordFailure(neutral, ProviderGatewayFailureReason.MISSING_CREDENTIAL)

        assertEquals(ProviderHealthState.HEALTHY, policy.snapshot(
            ForecastProvider.NOAA_NOMADS,
            "weather.example",
        ).state)

        repeat(3) {
            val permit = assertNotNull(
                policy.tryAcquire(ForecastProvider.NOAA_NOMADS, "weather.example"),
            )
            policy.recordFailure(permit, ProviderGatewayFailureReason.IO)
        }

        assertEquals(ProviderHealthState.OPEN, policy.snapshot(
            ForecastProvider.NOAA_NOMADS,
            "weather.example",
        ).state)
        assertEquals(ProviderHealthState.HEALTHY, policy.snapshot(
            ForecastProvider.ECMWF_OPEN_DATA,
            "weather.example",
        ).state)
        assertEquals(ProviderHealthState.HEALTHY, policy.snapshot(
            ForecastProvider.NOAA_NOMADS,
            "other.example",
        ).state)
    }

    private fun policy(
        now: AtomicLong,
        threshold: Int = 3,
        cooldown: Duration = Duration.ofSeconds(30),
    ): ProviderHealthPolicy =
        ProviderHealthPolicy(
            failureThreshold = threshold,
            openCooldown = cooldown,
            monotonicNanos = now::get,
        )
}
