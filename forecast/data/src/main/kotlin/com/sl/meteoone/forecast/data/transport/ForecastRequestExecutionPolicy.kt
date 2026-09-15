package com.sl.meteoone.forecast.data.transport

import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsFailureReason
import com.sl.meteoone.core.network.BoundedHttpsResult
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val MAX_ATTEMPTS = 2
private val DEFAULT_RETRY_DELAY = Duration.ofSeconds(1)

/**
 * Forecast-layer request policy. The generic HTTPS transport deliberately remains retry-free.
 *
 * A retry is allowed only for an IO failure from a fully validated forecast HTTP call. Every retry
 * creates a fresh underlying one-shot call. Provider-declared request spacing is enforced before
 * every attempt and composes with the retry delay by taking the stricter interval.
 */
internal class ForecastRequestExecutionPolicy(
    private val startPacer: ForecastRequestStartPacer = ProcessWideForecastRequestStartPacer,
    private val retryDelay: Duration = DEFAULT_RETRY_DELAY,
) {
    init {
        require(!retryDelay.isNegative) { "Forecast retry delay must not be negative" }
    }

    fun newCall(
        host: String,
        minimumRequestSpacing: Duration,
        callFactory: () -> BoundedHttpsCall,
    ): BoundedHttpsCall {
        require(host.isNotBlank()) { "Forecast request host is required" }
        require(!minimumRequestSpacing.isNegative) { "Forecast request spacing must not be negative" }
        return PolicyBoundedHttpsCall(
            host = host,
            minimumRequestSpacing = minimumRequestSpacing,
            retryDelay = retryDelay,
            startPacer = startPacer,
            callFactory = callFactory,
        )
    }
}

internal fun interface ForecastRequestStartPacer {
    /** Returns false when cancellation wins before the reserved request start. */
    fun awaitStart(
        host: String,
        minimumSpacing: Duration,
        cancellationSignal: CountDownLatch,
    ): Boolean
}

/**
 * Process-wide host pacing prevents separate M1 engine instances from bypassing provider spacing.
 */
private object ProcessWideForecastRequestStartPacer : ForecastRequestStartPacer {
    private val delegate = MonotonicForecastRequestStartPacer()

    override fun awaitStart(
        host: String,
        minimumSpacing: Duration,
        cancellationSignal: CountDownLatch,
    ): Boolean = delegate.awaitStart(host, minimumSpacing, cancellationSignal)
}

internal class MonotonicForecastRequestStartPacer(
    private val nanoTime: () -> Long = System::nanoTime,
    private val awaitDelay: (Long, CountDownLatch) -> Boolean = { delayNanos, cancellationSignal ->
        if (delayNanos <= 0L) {
            cancellationSignal.count != 0L
        } else {
            !cancellationSignal.await(delayNanos, TimeUnit.NANOSECONDS)
        }
    },
) : ForecastRequestStartPacer {
    private val lock = Any()
    private val lastReservedStartNanosByHost = mutableMapOf<String, Long>()

    override fun awaitStart(
        host: String,
        minimumSpacing: Duration,
        cancellationSignal: CountDownLatch,
    ): Boolean {
        if (cancellationSignal.count == 0L) return false
        val spacingNanos = minimumSpacing.toNanos()
        val now = nanoTime()
        val delayNanos = synchronized(lock) {
            val previousStart = lastReservedStartNanosByHost[host]
            val earliestStart = previousStart?.let { saturatingAdd(it, spacingNanos) } ?: now
            val reservedStart = maxOf(now, earliestStart)
            lastReservedStartNanosByHost[host] = reservedStart
            (reservedStart - now).coerceAtLeast(0L)
        }
        return awaitDelay(delayNanos, cancellationSignal) && cancellationSignal.count != 0L
    }

    private fun saturatingAdd(value: Long, increment: Long): Long =
        if (increment > 0L && value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
}

private class PolicyBoundedHttpsCall(
    private val host: String,
    private val minimumRequestSpacing: Duration,
    private val retryDelay: Duration,
    private val startPacer: ForecastRequestStartPacer,
    private val callFactory: () -> BoundedHttpsCall,
) : BoundedHttpsCall {
    private val executed = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val cancellationSignal = CountDownLatch(1)
    private val activeCall = AtomicReference<BoundedHttpsCall?>(null)

    override fun execute(): BoundedHttpsResult {
        check(executed.compareAndSet(false, true)) { "Forecast HTTP calls are one-shot" }
        if (cancelled.get()) return cancelledResult()

        for (attempt in 0 until MAX_ATTEMPTS) {
            val spacing = if (attempt == 0) {
                minimumRequestSpacing
            } else {
                maxOf(minimumRequestSpacing, retryDelay)
            }
            if (!startPacer.awaitStart(host, spacing, cancellationSignal) || cancelled.get()) {
                return cancelledResult()
            }

            val call = callFactory()
            check(activeCall.compareAndSet(null, call)) { "Forecast HTTP attempt already active" }
            if (cancelled.get()) {
                call.cancel()
                activeCall.compareAndSet(call, null)
                return cancelledResult()
            }

            val result = try {
                call.execute()
            } finally {
                activeCall.compareAndSet(call, null)
            }
            if (cancelled.get()) return cancelledResult()

            val retryable = result is BoundedHttpsResult.Failure &&
                result.reason == BoundedHttpsFailureReason.IO
            if (!retryable || attempt == MAX_ATTEMPTS - 1) return result
        }

        error("Bounded forecast request attempt loop exhausted unexpectedly")
    }

    override fun cancel() {
        cancelled.set(true)
        cancellationSignal.countDown()
        activeCall.get()?.cancel()
    }

    private fun cancelledResult(): BoundedHttpsResult.Failure =
        BoundedHttpsResult.Failure(BoundedHttpsFailureReason.CANCELLED)
}
