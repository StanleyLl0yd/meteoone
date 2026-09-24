package com.sl.meteoone.backend.provider

import com.sl.meteoone.core.model.ForecastProvider
import java.time.Duration
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DEFAULT_FAILURE_THRESHOLD = 3
private const val MAX_FAILURE_THRESHOLD = 10
private const val DEFAULT_MAX_TRACKED_KEYS = 64
private const val MAX_TRACKED_KEYS = 256
private val DEFAULT_OPEN_COOLDOWN: Duration = Duration.ofSeconds(30)
private val MAX_OPEN_COOLDOWN: Duration = Duration.ofMinutes(10)

internal enum class ProviderHealthState {
    HEALTHY,
    DEGRADED,
    OPEN,
    HALF_OPEN,
}

internal data class ProviderHealthSnapshot(
    val state: ProviderHealthState,
    val consecutiveFailures: Int,
    val remainingCooldown: Duration,
)

internal class ProviderHealthPermit internal constructor(
    val provider: ForecastProvider,
    val host: String,
    val halfOpenProbe: Boolean,
    val tracked: Boolean,
)

internal class ProviderHealthPolicy(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val openCooldown: Duration = DEFAULT_OPEN_COOLDOWN,
    private val maxTrackedKeys: Int = DEFAULT_MAX_TRACKED_KEYS,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    init {
        require(failureThreshold in 2..MAX_FAILURE_THRESHOLD) {
            "Provider health failure threshold must be between 2 and $MAX_FAILURE_THRESHOLD"
        }
        require(!openCooldown.isZero && !openCooldown.isNegative && openCooldown <= MAX_OPEN_COOLDOWN) {
            "Provider health open cooldown must be positive and at most $MAX_OPEN_COOLDOWN"
        }
        require(maxTrackedKeys in 1..MAX_TRACKED_KEYS) {
            "Provider health tracked-key limit must be between 1 and $MAX_TRACKED_KEYS"
        }
    }

    private data class HealthKey(
        val provider: ForecastProvider,
        val host: String,
    )

    private data class MutableHealth(
        var state: ProviderHealthState = ProviderHealthState.HEALTHY,
        var consecutiveFailures: Int = 0,
        var openUntilNanos: Long = 0L,
    )

    private val mutex = Mutex()
    private val entries = LinkedHashMap<HealthKey, MutableHealth>(
        16,
        0.75f,
        true,
    )

    suspend fun tryAcquire(
        provider: ForecastProvider,
        host: String,
    ): ProviderHealthPermit? {
        val key = key(provider, host)
        return mutex.withLock {
            val health = getOrCreate(key)
                ?: return@withLock permit(
                    key = key,
                    halfOpenProbe = false,
                    tracked = false,
                )
            when (health.state) {
                ProviderHealthState.HEALTHY,
                ProviderHealthState.DEGRADED,
                -> permit(key, halfOpenProbe = false, tracked = true)

                ProviderHealthState.OPEN -> {
                    val now = monotonicNanos()
                    if (now < health.openUntilNanos) {
                        null
                    } else {
                        health.state = ProviderHealthState.HALF_OPEN
                        permit(key, halfOpenProbe = true, tracked = true)
                    }
                }

                ProviderHealthState.HALF_OPEN -> null
            }
        }
    }

    suspend fun recordSuccess(permit: ProviderHealthPermit) {
        if (!permit.tracked) return
        val key = key(permit.provider, permit.host)
        mutex.withLock {
            val health = entries[key] ?: return@withLock
            if (permit.halfOpenProbe) {
                if (health.state == ProviderHealthState.HALF_OPEN) {
                    health.reset()
                }
            } else if (
                health.state == ProviderHealthState.HEALTHY ||
                health.state == ProviderHealthState.DEGRADED
            ) {
                health.reset()
            }
        }
    }

    suspend fun recordFailure(
        permit: ProviderHealthPermit,
        reason: ProviderGatewayFailureReason,
    ) {
        if (!permit.tracked) return
        val key = key(permit.provider, permit.host)
        mutex.withLock {
            val health = entries[key] ?: return@withLock
            if (!reason.isHealthImpacting()) {
                if (permit.halfOpenProbe && health.state == ProviderHealthState.HALF_OPEN) {
                    health.state = ProviderHealthState.OPEN
                    health.openUntilNanos = monotonicNanos()
                }
                return@withLock
            }

            if (permit.halfOpenProbe) {
                if (health.state == ProviderHealthState.HALF_OPEN) {
                    health.state = ProviderHealthState.OPEN
                    health.openUntilNanos = nextOpenUntil(monotonicNanos())
                }
                return@withLock
            }

            if (
                health.state != ProviderHealthState.HEALTHY &&
                health.state != ProviderHealthState.DEGRADED
            ) {
                return@withLock
            }

            health.consecutiveFailures += 1
            if (health.consecutiveFailures >= failureThreshold) {
                health.state = ProviderHealthState.OPEN
                health.openUntilNanos = nextOpenUntil(monotonicNanos())
            } else {
                health.state = ProviderHealthState.DEGRADED
            }
        }
    }

    suspend fun trackedKeyCount(): Int =
        mutex.withLock {
            entries.size
        }

    suspend fun snapshot(
        provider: ForecastProvider,
        host: String,
    ): ProviderHealthSnapshot {
        val key = key(provider, host)
        return mutex.withLock {
            val health = entries[key] ?: return@withLock ProviderHealthSnapshot(
                state = ProviderHealthState.HEALTHY,
                consecutiveFailures = 0,
                remainingCooldown = Duration.ZERO,
            )
            val remaining = if (health.state == ProviderHealthState.OPEN) {
                Duration.ofNanos(
                    (health.openUntilNanos - monotonicNanos()).coerceAtLeast(0L),
                )
            } else {
                Duration.ZERO
            }
            ProviderHealthSnapshot(
                state = health.state,
                consecutiveFailures = health.consecutiveFailures,
                remainingCooldown = remaining,
            )
        }
    }

    private fun getOrCreate(key: HealthKey): MutableHealth? {
        entries[key]?.let { return it }
        if (entries.size >= maxTrackedKeys) {
            val evictable = entries.entries.firstOrNull { (_, health) ->
                health.state == ProviderHealthState.HEALTHY
            } ?: return null
            entries.remove(evictable.key)
        }
        return MutableHealth().also { health ->
            entries[key] = health
        }
    }

    private fun key(
        provider: ForecastProvider,
        host: String,
    ): HealthKey {
        require(provider != ForecastProvider.UNKNOWN) {
            "Provider health requires a known provider"
        }
        val normalizedHost = host.lowercase(Locale.ROOT)
        require(normalizedHost.isNotBlank()) {
            "Provider health requires a non-blank host"
        }
        return HealthKey(
            provider = provider,
            host = normalizedHost,
        )
    }

    private fun permit(
        key: HealthKey,
        halfOpenProbe: Boolean,
        tracked: Boolean,
    ): ProviderHealthPermit =
        ProviderHealthPermit(
            provider = key.provider,
            host = key.host,
            halfOpenProbe = halfOpenProbe,
            tracked = tracked,
        )

    private fun nextOpenUntil(now: Long): Long {
        val increment = openCooldown.toNanos()
        return if (now > Long.MAX_VALUE - increment) Long.MAX_VALUE else now + increment
    }

    private fun MutableHealth.reset() {
        state = ProviderHealthState.HEALTHY
        consecutiveFailures = 0
        openUntilNanos = 0L
    }
}

private fun ProviderGatewayFailureReason.isHealthImpacting(): Boolean =
    when (this) {
        ProviderGatewayFailureReason.IO,
        ProviderGatewayFailureReason.RESPONSE_TOO_LARGE,
        ProviderGatewayFailureReason.INVALID_RESPONSE,
        -> true

        ProviderGatewayFailureReason.MISSING_CREDENTIAL,
        ProviderGatewayFailureReason.CANCELLED,
        ProviderGatewayFailureReason.CIRCUIT_OPEN,
        -> false
    }

internal val processProviderHealthPolicy = ProviderHealthPolicy()
