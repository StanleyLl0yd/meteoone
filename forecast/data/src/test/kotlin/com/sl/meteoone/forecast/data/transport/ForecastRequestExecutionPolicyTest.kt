package com.sl.meteoone.forecast.data.transport

import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsFailureReason
import com.sl.meteoone.core.network.BoundedHttpsResponse
import com.sl.meteoone.core.network.BoundedHttpsResult
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ForecastRequestExecutionPolicyTest {
    @Test
    fun retriesIoExactlyOnceWithFreshCallAndStricterProviderSpacing() {
        val starts = mutableListOf<Pair<String, Duration>>()
        val pacer = ForecastRequestStartPacer { host, spacing, cancellation ->
            starts += host to spacing
            cancellation.count != 0L
        }
        val results = ArrayDeque<BoundedHttpsResult>().apply {
            add(failure(BoundedHttpsFailureReason.IO))
            add(success())
        }
        val createdCalls = AtomicInteger()
        val policy = ForecastRequestExecutionPolicy(
            startPacer = pacer,
            retryDelay = Duration.ofSeconds(1),
        )

        val result = policy.newCall(
            host = "nomads.ncep.noaa.gov",
            minimumRequestSpacing = Duration.ofSeconds(10),
        ) {
            createdCalls.incrementAndGet()
            ResultCall(results.removeFirst())
        }.execute()

        assertIs<BoundedHttpsResult.Success>(result)
        assertEquals(2, createdCalls.get())
        assertEquals(
            listOf(
                "nomads.ncep.noaa.gov" to Duration.ofSeconds(10),
                "nomads.ncep.noaa.gov" to Duration.ofSeconds(10),
            ),
            starts,
        )
    }

    @Test
    fun retryDelayAppliesWhenProviderHasNoLongerSpacing() {
        val starts = mutableListOf<Duration>()
        val pacer = ForecastRequestStartPacer { _, spacing, cancellation ->
            starts += spacing
            cancellation.count != 0L
        }
        val results = ArrayDeque<BoundedHttpsResult>().apply {
            add(failure(BoundedHttpsFailureReason.IO))
            add(success())
        }
        val policy = ForecastRequestExecutionPolicy(
            startPacer = pacer,
            retryDelay = Duration.ofSeconds(1),
        )

        assertIs<BoundedHttpsResult.Success>(
            policy.newCall("api.open-meteo.com", Duration.ZERO) {
                ResultCall(results.removeFirst())
            }.execute(),
        )
        assertEquals(listOf(Duration.ZERO, Duration.ofSeconds(1)), starts)
    }

    @Test
    fun nonIoFailuresAreNeverRetried() {
        for (reason in listOf(
            BoundedHttpsFailureReason.CANCELLED,
            BoundedHttpsFailureReason.RESPONSE_TOO_LARGE,
            BoundedHttpsFailureReason.INVALID_RESPONSE,
        )) {
            val calls = AtomicInteger()
            val policy = ForecastRequestExecutionPolicy(
                startPacer = immediatePacer(),
                retryDelay = Duration.ZERO,
            )
            val result = policy.newCall("example.com", Duration.ZERO) {
                calls.incrementAndGet()
                ResultCall(failure(reason))
            }.execute()

            assertEquals(reason, assertIs<BoundedHttpsResult.Failure>(result).reason)
            assertEquals(1, calls.get(), "unexpected retry for $reason")
        }
    }

    @Test
    fun secondIoFailureStopsAtTwoTotalAttempts() {
        val calls = AtomicInteger()
        val policy = ForecastRequestExecutionPolicy(
            startPacer = immediatePacer(),
            retryDelay = Duration.ZERO,
        )
        val result = policy.newCall("example.com", Duration.ZERO) {
            calls.incrementAndGet()
            ResultCall(failure(BoundedHttpsFailureReason.IO))
        }.execute()

        assertEquals(
            BoundedHttpsFailureReason.IO,
            assertIs<BoundedHttpsResult.Failure>(result).reason,
        )
        assertEquals(2, calls.get())
    }

    @Test
    fun cancellationWhileWaitingForRetryPreventsSecondCallCreation() {
        val secondStartEntered = CountDownLatch(1)
        val pacerCalls = AtomicInteger()
        val pacer = ForecastRequestStartPacer { _, _, cancellation ->
            if (pacerCalls.incrementAndGet() == 1) {
                true
            } else {
                secondStartEntered.countDown()
                !cancellation.await(5, TimeUnit.SECONDS)
            }
        }
        val createdCalls = AtomicInteger()
        val policyCall = ForecastRequestExecutionPolicy(
            startPacer = pacer,
            retryDelay = Duration.ofSeconds(1),
        ).newCall("example.com", Duration.ZERO) {
            createdCalls.incrementAndGet()
            ResultCall(failure(BoundedHttpsFailureReason.IO))
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<BoundedHttpsResult> { policyCall.execute() }
            assertTrue(secondStartEntered.await(5, TimeUnit.SECONDS))

            policyCall.cancel()

            assertEquals(
                BoundedHttpsFailureReason.CANCELLED,
                assertIs<BoundedHttpsResult.Failure>(future.get(5, TimeUnit.SECONDS)).reason,
            )
            assertEquals(1, createdCalls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationDelegatesToActiveAttempt() {
        val entered = CountDownLatch(1)
        val child = BlockingCall(entered)
        val policyCall = ForecastRequestExecutionPolicy(
            startPacer = immediatePacer(),
            retryDelay = Duration.ZERO,
        ).newCall("example.com", Duration.ZERO) { child }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<BoundedHttpsResult> { policyCall.execute() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            policyCall.cancel()

            assertTrue(child.cancelled)
            assertEquals(
                BoundedHttpsFailureReason.CANCELLED,
                assertIs<BoundedHttpsResult.Failure>(future.get(5, TimeUnit.SECONDS)).reason,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun monotonicPacerEnforcesStartToStartSpacingPerHost() {
        var nowNanos = 100L
        val waits = mutableListOf<Long>()
        val pacer = MonotonicForecastRequestStartPacer(
            nanoTime = { nowNanos },
            awaitDelay = { delayNanos, cancellation ->
                waits += delayNanos
                nowNanos += delayNanos
                cancellation.count != 0L
            },
        )
        val cancellation = CountDownLatch(1)

        assertTrue(pacer.awaitStart("a.example", Duration.ofNanos(50), cancellation))
        assertTrue(pacer.awaitStart("a.example", Duration.ofNanos(50), cancellation))
        assertTrue(pacer.awaitStart("b.example", Duration.ofNanos(50), cancellation))

        assertEquals(listOf(0L, 50L, 0L), waits)
    }

    @Test
    fun callIsOneShotLikeUnderlyingHttpCall() {
        val call = ForecastRequestExecutionPolicy(
            startPacer = immediatePacer(),
            retryDelay = Duration.ZERO,
        ).newCall("example.com", Duration.ZERO) { ResultCall(success()) }

        assertIs<BoundedHttpsResult.Success>(call.execute())
        assertFailsWith<IllegalStateException> { call.execute() }
    }

    private fun immediatePacer(): ForecastRequestStartPacer =
        ForecastRequestStartPacer { _, _, cancellation -> cancellation.count != 0L }

    private class ResultCall(
        private val result: BoundedHttpsResult,
    ) : BoundedHttpsCall {
        override fun execute(): BoundedHttpsResult = result

        override fun cancel() = Unit
    }

    private class BlockingCall(
        private val entered: CountDownLatch,
    ) : BoundedHttpsCall {
        private val released = CountDownLatch(1)

        @Volatile
        var cancelled: Boolean = false
            private set

        override fun execute(): BoundedHttpsResult {
            entered.countDown()
            check(released.await(5, TimeUnit.SECONDS)) { "blocking call was not cancelled" }
            return failure(BoundedHttpsFailureReason.CANCELLED)
        }

        override fun cancel() {
            cancelled = true
            released.countDown()
        }
    }

    private companion object {
        fun failure(reason: BoundedHttpsFailureReason): BoundedHttpsResult.Failure =
            BoundedHttpsResult.Failure(reason)

        fun success(): BoundedHttpsResult.Success = BoundedHttpsResult.Success(
            BoundedHttpsResponse(
                statusCode = 200,
                headers = emptyMap(),
                body = byteArrayOf(1),
            ),
        )
    }
}
