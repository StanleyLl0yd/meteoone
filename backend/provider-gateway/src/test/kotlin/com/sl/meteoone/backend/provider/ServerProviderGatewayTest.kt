package com.sl.meteoone.backend.provider

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsFailureReason
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResponse
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import java.net.URI
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ServerProviderGatewayTest {
    @Test
    fun credentialIsInjectedOnlyAtTransportBoundary() = runBlocking {
        val transport = RecordingTransport(
            success(body = "ok".encodeToByteArray()),
        )
        val credentialValue = "credential-value"
        val request = request(
            credential = ProviderCredentialRequirement(
                slot = ProviderCredentialSlot("WEATHER_KEY"),
                headerName = "Authorization",
                valuePrefix = "Bearer ",
            ),
        )
        val gateway = gateway(
            transport = transport,
            secretSource = ProviderSecretSource { slot ->
                credentialValue.takeIf { slot == ProviderCredentialSlot("WEATHER_KEY") }
            },
        )

        val result = gateway.execute(request)

        assertIs<ProviderGatewayResult.Success>(result)
        assertEquals(
            "Bearer $credentialValue",
            transport.requests.single().headers["Authorization"],
        )
        assertFalse(request.toString().contains(credentialValue))
        assertFalse(result.toString().contains(credentialValue))
    }

    @Test
    fun missingCredentialFailsBeforeNetworkCall() = runBlocking {
        val transport = RecordingTransport(success())
        val result = gateway(
            transport = transport,
            secretSource = ProviderSecretSource.NONE,
        ).execute(
            request(
                credential = ProviderCredentialRequirement(
                    slot = ProviderCredentialSlot("WEATHER_KEY"),
                    headerName = "Authorization",
                ),
            ),
        )

        val failure = assertIs<ProviderGatewayResult.Failure>(result)
        assertEquals(ProviderGatewayFailureReason.MISSING_CREDENTIAL, failure.reason)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun ioFailureRetriesExactlyOnceWithFreshCall() = runBlocking {
        val transport = RecordingTransport(
            BoundedHttpsResult.Failure(BoundedHttpsFailureReason.IO),
            success(),
        )

        val result = gateway(transport).execute(request())

        assertIs<ProviderGatewayResult.Success>(result)
        assertEquals(2, transport.requests.size)
        assertEquals(2, transport.createdCalls)
    }

    @Test
    fun unexpectedHttpStatusFailsClosedWithoutRetry() = runBlocking {
        val transport = RecordingTransport(
            success(statusCode = 503),
            success(),
        )

        val result = gateway(transport).execute(request())

        val failure = assertIs<ProviderGatewayResult.Failure>(result)
        assertEquals(ProviderGatewayFailureReason.INVALID_RESPONSE, failure.reason)
        assertEquals(1, transport.createdCalls)
    }

    @Test
    fun nonIoFailureIsNotRetried() = runBlocking {
        val transport = RecordingTransport(
            BoundedHttpsResult.Failure(BoundedHttpsFailureReason.INVALID_RESPONSE),
            success(),
        )

        val result = gateway(transport).execute(request())

        val failure = assertIs<ProviderGatewayResult.Failure>(result)
        assertEquals(ProviderGatewayFailureReason.INVALID_RESPONSE, failure.reason)
        assertEquals(1, transport.createdCalls)
    }

    @Test
    fun pacingIsScopedByProviderAndHost() = runBlocking {
        val now = AtomicLong(0L)
        val waits = mutableListOf<Duration>()
        val pacer = ProviderRequestPacer(
            monotonicNanos = now::get,
            waitFor = { duration ->
                waits += duration
                now.addAndGet(duration.toNanos())
            },
        )

        pacer.awaitTurn(
            ForecastProvider.NOAA_NOMADS,
            "weather.example",
            Duration.ofSeconds(10),
        )
        pacer.awaitTurn(
            ForecastProvider.ECMWF_OPEN_DATA,
            "weather.example",
            Duration.ofSeconds(10),
        )
        pacer.awaitTurn(
            ForecastProvider.NOAA_NOMADS,
            "weather.example",
            Duration.ofSeconds(10),
        )

        assertEquals(listOf(Duration.ofSeconds(10)), waits)
    }

    @Test
    fun circuitOpensAfterConsecutiveFinalFailuresAndRecoversWithHalfOpenProbe() = runBlocking {
        val now = AtomicLong(0L)
        val cooldown = Duration.ofSeconds(5)
        val healthPolicy = ProviderHealthPolicy(
            failureThreshold = 2,
            openCooldown = cooldown,
            monotonicNanos = now::get,
        )
        val transport = RecordingTransport(
            BoundedHttpsResult.Failure(BoundedHttpsFailureReason.IO),
            BoundedHttpsResult.Failure(BoundedHttpsFailureReason.IO),
            BoundedHttpsResult.Failure(BoundedHttpsFailureReason.IO),
            BoundedHttpsResult.Failure(BoundedHttpsFailureReason.IO),
            success(body = "recovered".encodeToByteArray()),
        )
        val gateway = gateway(
            transport = transport,
            healthPolicy = healthPolicy,
        )

        val first = gateway.execute(request())
        val second = gateway.execute(request())
        val blocked = gateway.execute(request())

        assertEquals(ProviderGatewayFailureReason.IO, assertIs<ProviderGatewayResult.Failure>(first).reason)
        assertEquals(ProviderGatewayFailureReason.IO, assertIs<ProviderGatewayResult.Failure>(second).reason)
        assertEquals(
            ProviderGatewayFailureReason.CIRCUIT_OPEN,
            assertIs<ProviderGatewayResult.Failure>(blocked).reason,
        )
        assertEquals(4, transport.createdCalls)

        now.addAndGet(cooldown.toNanos())

        val recovered = gateway.execute(request())

        assertIs<ProviderGatewayResult.Success>(recovered)
        assertEquals(5, transport.createdCalls)
        assertEquals(
            ProviderHealthState.HEALTHY,
            healthPolicy.snapshot(ForecastProvider.NOAA_NOMADS, "weather.example").state,
        )
    }

    @Test
    fun cancellationCancelsActiveTransportCall() = runBlocking {
        val transport = BlockingTransport()
        val gateway = gateway(transport)
        val request = request()

        val execution = async {
            gateway.execute(request)
        }

        assertTrue(
            withContext(Dispatchers.IO) {
                transport.entered.await(5, TimeUnit.SECONDS)
            },
        )
        execution.cancel()
        execution.join()

        assertTrue(transport.cancelled.get())
    }

    @Test
    fun requestRejectsUnboundedResponseLimitAndExcessivePacing() {
        assertFailsWith<IllegalArgumentException> {
            request(maxResponseBytes = Long.MAX_VALUE)
        }
        assertFailsWith<IllegalArgumentException> {
            request(minimumRequestSpacing = Duration.ofMinutes(11))
        }
    }

    private fun gateway(
        transport: BoundedHttpsTransport,
        secretSource: ProviderSecretSource = ProviderSecretSource.NONE,
        healthPolicy: ProviderHealthPolicy = ProviderHealthPolicy(),
    ): ServerProviderGateway =
        ServerProviderGateway(
            transport = transport,
            secretSource = secretSource,
            pacer = ProviderRequestPacer(),
            healthPolicy = healthPolicy,
        )

    private fun request(
        maxResponseBytes: Long = 1024,
        minimumRequestSpacing: Duration = Duration.ZERO,
        credential: ProviderCredentialRequirement? = null,
    ): ProviderGatewayRequest =
        ProviderGatewayRequest(
            provider = ForecastProvider.NOAA_NOMADS,
            modelFamily = ModelFamily.NOAA_GFS,
            uri = URI.create("https://weather.example/data"),
            maxResponseBytes = maxResponseBytes,
            minimumRequestSpacing = minimumRequestSpacing,
            credential = credential,
        )

    private fun success(
        body: ByteArray = byteArrayOf(),
        statusCode: Int = 200,
    ): BoundedHttpsResult =
        BoundedHttpsResult.Success(
            BoundedHttpsResponse(
                statusCode = statusCode,
                headers = mapOf("Content-Type" to listOf("application/octet-stream")),
                body = body,
            ),
        )

    private class RecordingTransport(
        vararg results: BoundedHttpsResult,
    ) : BoundedHttpsTransport {
        private val results = ArrayDeque(results.toList())
        val requests = mutableListOf<BoundedHttpsRequest>()
        var createdCalls: Int = 0
            private set

        override fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall {
            requests += request
            createdCalls += 1
            val result = results.removeFirst()
            return object : BoundedHttpsCall {
                override fun execute(): BoundedHttpsResult = result
                override fun cancel() = Unit
            }
        }
    }

    private class BlockingTransport : BoundedHttpsTransport {
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        private val release = CountDownLatch(1)

        override fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall =
            object : BoundedHttpsCall {
                override fun execute(): BoundedHttpsResult {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return BoundedHttpsResult.Failure(BoundedHttpsFailureReason.CANCELLED)
                }

                override fun cancel() {
                    cancelled.set(true)
                    release.countDown()
                }
            }
    }
}
