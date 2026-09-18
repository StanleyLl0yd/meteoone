package com.sl.meteoone.forecast.data.transport

import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsFailureReason
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResponse
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.network.BoundedHttpsTransport
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoModel
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoSingleRunRequestPlanner
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ForecastHttpAdapterPolicyTest {
    @Test
    fun officialRequestSpacingFlowsIntoExecutionPolicy() {
        val observedSpacing = mutableListOf<Duration>()
        val policy = ForecastRequestExecutionPolicy(
            startPacer = ForecastRequestStartPacer { _, spacing, cancellation ->
                observedSpacing += spacing
                cancellation.count != 0L
            },
            retryDelay = Duration.ZERO,
        )
        val adapter = ForecastHttpAdapter(
            transport = ScriptedTransport(listOf(success(200))),
            executionPolicy = policy,
        )
        val request = OfficialSourceRequest(
            uri = URI.create("https://nomads.ncep.noaa.gov/data"),
            maxResponseBytes = 1024,
            minimumRequestSpacing = Duration.ofSeconds(10),
        )

        assertIs<BoundedHttpsResult.Success>(adapter.newOrdinaryCall(request).execute())
        assertEquals(listOf(Duration.ofSeconds(10)), observedSpacing)
    }

    @Test
    fun exactRunRequestUsesOneSecondHostSpacing() {
        val observedSpacing = mutableListOf<Duration>()
        val policy = ForecastRequestExecutionPolicy(
            startPacer = ForecastRequestStartPacer { _, spacing, cancellation ->
                observedSpacing += spacing
                cancellation.count != 0L
            },
            retryDelay = Duration.ZERO,
        )
        val adapter = ForecastHttpAdapter(
            transport = ScriptedTransport(listOf(success(200))),
            executionPolicy = policy,
        )
        val request = OpenMeteoSingleRunRequestPlanner.plan(
            model = OpenMeteoModel.ECMWF_IFS,
            coordinate = ForecastCoordinate(59.9, 30.3),
            modelRun = Instant.parse("2026-09-15T00:00:00Z"),
        )

        assertIs<BoundedHttpsResult.Success>(adapter.newOrdinaryCall(request).execute())
        assertEquals(listOf(Duration.ofSeconds(1)), observedSpacing)
    }

    @Test
    fun validatedHttpFailureIsNotRetried() {
        val transport = ScriptedTransport(
            listOf(success(statusCode = 503), success(statusCode = 200)),
        )
        val adapter = ForecastHttpAdapter(
            transport = transport,
            executionPolicy = immediatePolicy(),
        )
        val request = OfficialSourceRequest(
            uri = URI.create("https://example.com/data"),
            maxResponseBytes = 1024,
        )

        val result = adapter.newOrdinaryCall(request).execute()

        assertEquals(
            BoundedHttpsFailureReason.INVALID_RESPONSE,
            assertIs<BoundedHttpsResult.Failure>(result).reason,
        )
        assertEquals(1, transport.createdCalls.get())
    }

    @Test
    fun ioRetryCreatesFreshTransportCallAndThenValidatesSuccess() {
        val transport = ScriptedTransport(
            listOf(
                BoundedHttpsResult.Failure(BoundedHttpsFailureReason.IO),
                success(statusCode = 200),
            ),
        )
        val adapter = ForecastHttpAdapter(
            transport = transport,
            executionPolicy = immediatePolicy(),
        )
        val request = OfficialSourceRequest(
            uri = URI.create("https://example.com/data"),
            maxResponseBytes = 1024,
        )

        assertIs<BoundedHttpsResult.Success>(adapter.newOrdinaryCall(request).execute())
        assertEquals(2, transport.createdCalls.get())
    }

    private fun immediatePolicy(): ForecastRequestExecutionPolicy =
        ForecastRequestExecutionPolicy(
            startPacer = ForecastRequestStartPacer { _, _, cancellation ->
                cancellation.count != 0L
            },
            retryDelay = Duration.ZERO,
        )

    private class ScriptedTransport(
        results: List<BoundedHttpsResult>,
    ) : BoundedHttpsTransport {
        private val results = ArrayDeque(results)
        val createdCalls = AtomicInteger()

        override fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall {
            createdCalls.incrementAndGet()
            val result = results.removeFirst()
            return object : BoundedHttpsCall {
                override fun execute(): BoundedHttpsResult = result

                override fun cancel() = Unit
            }
        }
    }

    private companion object {
        fun success(statusCode: Int): BoundedHttpsResult.Success = BoundedHttpsResult.Success(
            BoundedHttpsResponse(
                statusCode = statusCode,
                headers = emptyMap(),
                body = byteArrayOf(1),
            ),
        )
    }
}
