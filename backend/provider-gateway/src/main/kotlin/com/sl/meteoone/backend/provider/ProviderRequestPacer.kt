package com.sl.meteoone.backend.provider

import com.sl.meteoone.core.model.ForecastProvider
import java.time.Duration
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ProviderRequestPacer(
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val waitFor: suspend (Duration) -> Unit = { duration ->
        delay(duration.toMillis().coerceAtLeast(1L))
    },
) {
    private data class PaceKey(
        val provider: ForecastProvider,
        val host: String,
    )

    private val mutex = Mutex()
    private val nextStartNanos = mutableMapOf<PaceKey, Long>()

    suspend fun awaitTurn(
        provider: ForecastProvider,
        host: String,
        minimumSpacing: Duration,
    ) {
        if (minimumSpacing.isZero) return

        val wait = mutex.withLock {
            val key = PaceKey(
                provider = provider,
                host = host.lowercase(Locale.ROOT),
            )
            val now = monotonicNanos()
            val scheduled = maxOf(now, nextStartNanos[key] ?: now)
            nextStartNanos[key] = saturatingAdd(scheduled, minimumSpacing.toNanos())
            Duration.ofNanos((scheduled - now).coerceAtLeast(0L))
        }

        if (!wait.isZero) {
            waitFor(wait)
        }
    }

    private fun saturatingAdd(
        value: Long,
        increment: Long,
    ): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
}

internal val processProviderRequestPacer = ProviderRequestPacer()
