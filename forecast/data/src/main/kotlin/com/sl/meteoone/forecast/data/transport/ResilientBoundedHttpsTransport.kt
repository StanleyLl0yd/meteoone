package com.sl.meteoone.forecast.data.transport

import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsFailureReason
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

private val DEFAULT_FAST_IO_RETRY_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(2)
private val DEFAULT_RETRY_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(250)
private val DEFAULT_HOST_START_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250)
private val DEFAULT_HOST_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(15)
private val WAIT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50)

internal data class TransportResiliencePolicy(
    val maxIoRetries: Int = 1,
    val fastIoRetryBudgetNanos: Long = DEFAULT_FAST_IO_RETRY_BUDGET_NANOS,
    val retryDelayNanos: Long = DEFAULT_RETRY_DELAY_NANOS,
    val hostStartIntervalNanos: Long = DEFAULT_HOST_START_INTERVAL_NANOS,
    val ioFailuresBeforeCooldown: Int = 2,
    val hostCooldownNanos: Long = DEFAULT_HOST_COOLDOWN_NANOS,
    val maxTrackedHosts: Int = 16,
) {
    init {
        require(maxIoRetries in 0..1) { "Transport IO retries must remain bounded to at most one" }
        require(fastIoRetryBudgetNanos >= 0) { "Fast IO retry budget must not be negative" }
        require(retryDelayNanos >= 0) { "Retry delay must not be negative" }
        require(hostStartIntervalNanos >= 0) { "Host start interval must not be negative" }
        require(ioFailuresBeforeCooldown >= 1) { "IO cooldown threshold must be positive" }
        require(hostCooldownNanos >= 0) { "Host cooldown must not be negative" }
        require(maxTrackedHosts >= 1) { "Tracked host bound must be positive" }
    }
}

internal fun interface MonotonicNanoClock {
    fun nowNanos(): Long
}

internal fun interface CancellableNanoWaiter {
    fun awaitNanos(
        delayNanos: Long,
        isCancelled: () -> Boolean,
    ): Boolean
}

private object SystemMonotonicNanoClock : MonotonicNanoClock {
    override fun nowNanos(): Long = System.nanoTime()
}

private object PollingCancellableNanoWaiter : CancellableNanoWaiter {
    override fun awaitNanos(
        delayNanos: Long,
        isCancelled: () -> Boolean,
    ): Boolean {
        var remaining = delayNanos.coerceAtLeast(0)
        while (remaining > 0) {
            if (isCancelled() || Thread.currentThread().isInterrupted) return false
            val chunk = minOf(remaining, WAIT_POLL_NANOS)
            val startedAt = System.nanoTime()
            LockSupport.parkNanos(chunk)
            val elapsed = (System.nanoTime() - startedAt).coerceAtLeast(1)
            remaining -= minOf(remaining, elapsed)
        }
        return !isCancelled() && !Thread.currentThread().isInterrupted
    }
}

internal sealed interface HostStartDecision {
    data class Allowed(val delayNanos: Long) : HostStartDecision

    data object CoolingDown : HostStartDecision
}

internal class HostRequestPolicyState(
    private val policy: TransportResiliencePolicy,
) {
    private data class HostState(
        var nextStartNanos: Long? = null,
        var consecutiveIoFailures: Int = 0,
        var cooldownUntilNanos: Long? = null,
    )

    private val hosts = LinkedHashMap<String, HostState>(16, 0.75f, true)

    @Synchronized
    fun reserveStart(
        host: String,
        nowNanos: Long,
    ): HostStartDecision {
        val state = stateFor(host)
        val cooldownUntil = state.cooldownUntilNanos
        if (cooldownUntil != null && nowNanos < cooldownUntil) {
            return HostStartDecision.CoolingDown
        }
        if (cooldownUntil != null) {
            state.cooldownUntilNanos = null
            state.consecutiveIoFailures = 0
        }

        val earliestStart = state.nextStartNanos?.let { maxOf(nowNanos, it) } ?: nowNanos
        state.nextStartNanos = addBounded(earliestStart, policy.hostStartIntervalNanos)
        return HostStartDecision.Allowed(
            delayNanos = (earliestStart - nowNanos).coerceAtLeast(0),
        )
    }

    @Synchronized
    fun recordResult(
        host: String,
        result: BoundedHttpsResult,
        nowNanos: Long,
    ) {
        val state = stateFor(host)
        when (result) {
            is BoundedHttpsResult.Success -> state.markReachable()
            is BoundedHttpsResult.Failure -> when (result.reason) {
                BoundedHttpsFailureReason.IO -> {
                    state.consecutiveIoFailures += 1
                    if (state.consecutiveIoFailures >= policy.ioFailuresBeforeCooldown) {
                        state.cooldownUntilNanos = addBounded(nowNanos, policy.hostCooldownNanos)
                    }
                }

                BoundedHttpsFailureReason.RESPONSE_TOO_LARGE,
                BoundedHttpsFailureReason.INVALID_RESPONSE,
                -> state.markReachable()

                BoundedHttpsFailureReason.CANCELLED -> Unit
            }
        }
    }

    @Synchronized
    fun trackedHostCount(): Int = hosts.size

    private fun stateFor(host: String): HostState {
        val canonicalHost = host.lowercase(Locale.ROOT)
        hosts[canonicalHost]?.let { return it }
        val created = HostState()
        hosts[canonicalHost] = created
        while (hosts.size > policy.maxTrackedHosts) {
            val eldest = hosts.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
        return created
    }

    private fun HostState.markReachable() {
        consecutiveIoFailures = 0
        cooldownUntilNanos = null
    }

    private fun addBounded(
        value: Long,
        delta: Long,
    ): Long = if (delta > 0 && value > Long.MAX_VALUE - delta) {
        Long.MAX_VALUE
    } else {
        value + delta
    }
}

private object ProductionTransportResilience {
    val policy = TransportResiliencePolicy()
    val hostState = HostRequestPolicyState(policy)
}

internal fun productionResilientTransport(delegate: BoundedHttpsTransport): BoundedHttpsTransport =
    ResilientBoundedHttpsTransport(
        delegate = delegate,
        policy = ProductionTransportResilience.policy,
        hostState = ProductionTransportResilience.hostState,
        clock = SystemMonotonicNanoClock,
        waiter = PollingCancellableNanoWaiter,
    )

internal class ResilientBoundedHttpsTransport(
    private val delegate: BoundedHttpsTransport,
    private val policy: TransportResiliencePolicy,
    private val hostState: HostRequestPolicyState,
    private val clock: MonotonicNanoClock,
    private val waiter: CancellableNanoWaiter,
) : BoundedHttpsTransport {
    override fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall =
        ResilientBoundedHttpsCall(
            request = request,
            delegate = delegate,
            policy = policy,
            hostState = hostState,
            clock = clock,
            waiter = waiter,
        )
}

private class ResilientBoundedHttpsCall(
    private val request: BoundedHttpsRequest,
    private val delegate: BoundedHttpsTransport,
    private val policy: TransportResiliencePolicy,
    private val hostState: HostRequestPolicyState,
    private val clock: MonotonicNanoClock,
    private val waiter: CancellableNanoWaiter,
) : BoundedHttpsCall {
    @Volatile
    private var cancelled = false

    @Volatile
    private var activeCall: BoundedHttpsCall? = null

    override fun execute(): BoundedHttpsResult {
        if (cancelled) return cancelledFailure()

        val host = requireNotNull(request.uri.host) { "Validated HTTPS request must have a host" }
        var retries = 0
        while (true) {
            when (val start = hostState.reserveStart(host, clock.nowNanos())) {
                HostStartDecision.CoolingDown -> return ioFailure()
                is HostStartDecision.Allowed -> {
                    if (!waiter.awaitNanos(start.delayNanos) { cancelled }) {
                        return cancelledFailure()
                    }
                }
            }
            if (cancelled) return cancelledFailure()

            val attemptStartedAt = clock.nowNanos()
            val call = delegate.newCall(request)
            activeCall = call
            if (cancelled) {
                call.cancel()
                activeCall = null
                return cancelledFailure()
            }

            val result = call.execute()
            activeCall = null
            val completedAt = clock.nowNanos()
            hostState.recordResult(host, result, completedAt)

            val fastRetryableIo =
                result is BoundedHttpsResult.Failure &&
                    result.reason == BoundedHttpsFailureReason.IO &&
                    retries < policy.maxIoRetries &&
                    elapsedNanos(attemptStartedAt, completedAt) <= policy.fastIoRetryBudgetNanos
            if (!fastRetryableIo) return result

            retries += 1
            if (!waiter.awaitNanos(policy.retryDelayNanos) { cancelled }) {
                return cancelledFailure()
            }
        }
    }

    override fun cancel() {
        cancelled = true
        activeCall?.cancel()
    }

    private fun elapsedNanos(
        startedAt: Long,
        completedAt: Long,
    ): Long = (completedAt - startedAt).coerceAtLeast(0)
}

private fun cancelledFailure(): BoundedHttpsResult.Failure =
    BoundedHttpsResult.Failure(BoundedHttpsFailureReason.CANCELLED)

private fun ioFailure(): BoundedHttpsResult.Failure =
    BoundedHttpsResult.Failure(BoundedHttpsFailureReason.IO)
