package com.sl.meteoone.backend.gateway

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounded in-memory gateway cache with per-key single-flight loading.
 *
 * Only successful loads enter the cache. A failed or cancelled leader completes current waiters
 * with the same failure and removes the in-flight entry so a later request can retry normally.
 */
class SingleFlightForecastGatewayCache<V : Any>(
    private val ttl: Duration,
    private val maxEntries: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(!ttl.isZero && !ttl.isNegative) {
            "Gateway cache TTL must be positive"
        }
        require(maxEntries > 0) {
            "Gateway cache maximum entry count must be positive"
        }
    }

    private data class Entry<V>(
        val value: V,
        val expiresAt: Instant,
    )

    private sealed interface Lookup<out V> {
        data class Cached<V>(val value: V) : Lookup<V>

        data class Pending<V>(
            val deferred: CompletableDeferred<V>,
            val leader: Boolean,
        ) : Lookup<V>
    }

    private val mutex = Mutex()
    private val entries = mutableMapOf<ForecastGatewayCacheKey, Entry<V>>()
    private val inFlight = mutableMapOf<ForecastGatewayCacheKey, CompletableDeferred<V>>()

    suspend fun getOrLoad(
        key: ForecastGatewayCacheKey,
        loader: suspend () -> V,
    ): V {
        val lookup = mutex.withLock {
            val now = clock.instant()
            evictExpired(now)

            entries[key]?.let { entry ->
                return@withLock Lookup.Cached(entry.value)
            }

            inFlight[key]?.let { existing ->
                return@withLock Lookup.Pending(existing, leader = false)
            }

            val created = CompletableDeferred<V>()
            inFlight[key] = created
            Lookup.Pending(created, leader = true)
        }

        return when (lookup) {
            is Lookup.Cached -> lookup.value
            is Lookup.Pending -> {
                if (!lookup.leader) {
                    lookup.deferred.await()
                } else {
                    loadAndPublish(
                        key = key,
                        deferred = lookup.deferred,
                        loader = loader,
                    )
                }
            }
        }
    }

    private suspend fun loadAndPublish(
        key: ForecastGatewayCacheKey,
        deferred: CompletableDeferred<V>,
        loader: suspend () -> V,
    ): V = try {
        val value = loader()
        val completedAt = clock.instant()

        mutex.withLock {
            evictExpired(completedAt)
            if (key !in entries && entries.size >= maxEntries) {
                val victim = entries.minByOrNull { (_, entry) -> entry.expiresAt }?.key
                if (victim != null) {
                    entries.remove(victim)
                }
            }
            entries[key] = Entry(
                value = value,
                expiresAt = completedAt.plus(ttl),
            )
            if (inFlight[key] === deferred) {
                inFlight.remove(key)
            }
            deferred.complete(value)
        }
        value
    } catch (error: Throwable) {
        mutex.withLock {
            if (inFlight[key] === deferred) {
                inFlight.remove(key)
            }
            deferred.completeExceptionally(error)
        }
        throw error
    }

    private fun evictExpired(now: Instant) {
        entries.entries.removeIf { (_, entry) ->
            !now.isBefore(entry.expiresAt)
        }
    }
}
