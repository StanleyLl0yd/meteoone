package com.sl.meteoone.backend.provider

import com.sl.meteoone.core.model.ForecastProvider
import java.time.Duration
import java.util.Locale

private val MAX_HEALTH_COOLDOWN: Duration = Duration.ofHours(1)

internal enum class ProviderHealthState {
    HEALTHY,
    DEGRADED,
    OPEN,
    HALF_OPEN,
}

internal data class ProviderHealthSnapshot(
    val state: ProviderHealthState,
    val consecutiveFailures: Int,
    val retryAfter: Duration?,
)

internal data class ProviderHealthPermit(
    val provider: ForecastProvider,
    val host: String,
    val probe: Boolean,
)

internal class ProviderHealthTracker(
    private val failureThreshold: Int = 3,
    private val cooldown: Duration = Duration.ofMinutes(1),
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    init {
        require(failureThreshold in 1..100) {
            "Provider health failure threshold must be between 1 and 100"
        }
        require(!cooldown.isZero && !cooldown.isNegative && cooldown <= MAX_HEALTH_COOLDOWN) {
            "Provider health cooldown must be positive and at most $MAX_HEALTH_COOLDOWN"
        }
    }

    private data class Key(
        val provider: ForecastProvider,
        val host: String,
    )

    private data class Entry(
        var consecutiveFailures: Int = 0,
        var openUntilNanos: Long? = null,
        var halfOpenProbeActive: Boolean = false,
    )

    private val lock = Any()
    private val entries = mutableMapOf<Key, Entry>()

    fun tryAcquire(
        provider: ForecastProvider,
        host: String,
    ): ProviderHealthPermit? {
        val key = key(provider, host)
        val now = monotonicNanos()

        synchronized(lock) {
            val entry = entries.getOrPut(key) { Entry() }
            val openUntil = entry.openUntilNanos
            if (openUntil == null) {
                return ProviderHealthPermit(provider, key.host, probe = false)
            }
            if (now < openUntil || entry.halfOpenProbeActive) {
                return null
            }

            entry.halfOpenProbeActive = true
            return ProviderHealthPermit(provider, key.host, probe = true)
        }
    }

    fun recordSuccess(permit: ProviderHealthPermit) {
        synchronized(lock) {
            entries[key(permit)] = Entry()
        }
    }

    fun recordHealthFailure(permit: ProviderHealthPermit) {
        val now = monotonicNanos()
        synchronized(lock) {
            val key = key(permit)
            val entry = entries.getOrPut(key) { Entry() }
            entry.halfOpenProbeActive = false

            if (permit.probe) {
                entry.consecutiveFailures = failureThreshold
                entry.openUntilNanos = saturatingAdd(now, cooldown.toNanos())
                return
            }

            entry.consecutiveFailures =
                (entry.consecutiveFailures + 1).coerceAtMost(failureThreshold)
            if (entry.consecutiveFailures >= failureThreshold) {
                entry.openUntilNanos = saturatingAdd(now, cooldown.toNanos())
            }
        }
    }

    fun recordNeutral(permit: ProviderHealthPermit) {
        if (!permit.probe) return
        synchronized(lock) {
            entries[key(permit)]?.halfOpenProbeActive = false
        }
    }

    fun snapshot(
        provider: ForecastProvider,
        host: String,
    ): ProviderHealthSnapshot {
        val key = key(provider, host)
        val now = monotonicNanos()

        synchronized(lock) {
            val entry = entries[key] ?: return ProviderHealthSnapshot(
                state = ProviderHealthState.HEALTHY,
                consecutiveFailures = 0,
                retryAfter = null,
            )
            if (entry.halfOpenProbeActive) {
                return ProviderHealthSnapshot(
                    state = ProviderHealthState.HALF_OPEN,
                    consecutiveFailures = entry.consecutiveFailures,
                    retryAfter = null,
                )
            }

            val openUntil = entry.openUntilNanos
            if (openUntil != null) {
                return ProviderHealthSnapshot(
                    state = ProviderHealthState.OPEN,
                    consecutiveFailures = entry.consecutiveFailures,
                    retryAfter = Duration.ofNanos((openUntil - now).coerceAtLeast(0L)),
                )
            }

            return ProviderHealthSnapshot(
                state = if (entry.consecutiveFailures == 0) {
                    ProviderHealthState.HEALTHY
                } else {
                    ProviderHealthState.DEGRADED
                },
                consecutiveFailures = entry.consecutiveFailures,
                retryAfter = null,
            )
        }
    }

    private fun key(
        provider: ForecastProvider,
        host: String,
    ): Key {
        require(provider != ForecastProvider.UNKNOWN) {
            "Provider health requires a known provider"
        }
        require(host.isNotBlank()) {
            "Provider health host must not be blank"
        }
        return Key(provider, host.lowercase(Locale.ROOT))
    }

    private fun key(permit: ProviderHealthPermit): Key =
        Key(permit.provider, permit.host)

    private fun saturatingAdd(
        value: Long,
        increment: Long,
    ): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
}

internal val processProviderHealthTracker = ProviderHealthTracker()
