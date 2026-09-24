package com.sl.meteoone.backend.provider

import com.sl.meteoone.core.network.BoundedHttpsFailureReason
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import com.sl.meteoone.core.network.DefaultBoundedHttpsTransport
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val MAX_ATTEMPTS = 2

class ServerProviderGateway internal constructor(
    private val transport: BoundedHttpsTransport,
    private val secretSource: ProviderSecretSource,
    private val pacer: ProviderRequestPacer,
    private val healthTracker: ProviderHealthTracker = ProviderHealthTracker(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun execute(request: ProviderGatewayRequest): ProviderGatewayResult {
        val headers = when (val credential = request.credential) {
            null -> emptyMap()
            else -> {
                val secret = secretSource.resolve(credential.slot)
                    ?.takeIf(String::isNotBlank)
                    ?: return request.failure(ProviderGatewayFailureReason.MISSING_CREDENTIAL)
                mapOf(
                    credential.headerName to credential.valuePrefix + secret,
                )
            }
        }

        val networkRequest = BoundedHttpsRequest(
            uri = request.uri,
            maxResponseBytes = request.maxResponseBytes,
            headers = headers,
        )

        val healthPermit = healthTracker.tryAcquire(
            provider = request.provider,
            host = request.uri.host,
        ) ?: return request.failure(ProviderGatewayFailureReason.CIRCUIT_OPEN)

        var healthRecorded = false
        try {
            repeat(MAX_ATTEMPTS) { attempt ->
                pacer.awaitTurn(
                    provider = request.provider,
                    host = request.uri.host,
                    minimumSpacing = request.minimumRequestSpacing,
                )

                when (val result = executeOnce(networkRequest)) {
                    is BoundedHttpsResult.Success -> {
                        val response = result.response
                        if (response.statusCode !in request.expectedStatusCodes) {
                            healthTracker.recordHealthFailure(healthPermit)
                            healthRecorded = true
                            return request.failure(ProviderGatewayFailureReason.INVALID_RESPONSE)
                        }
                        healthTracker.recordSuccess(healthPermit)
                        healthRecorded = true
                        return ProviderGatewayResult.Success(
                            ProviderGatewayResponse(
                                provider = request.provider,
                                modelFamily = request.modelFamily,
                                statusCode = response.statusCode,
                                headers = response.headers,
                                body = response.body,
                            ),
                        )
                    }

                    is BoundedHttpsResult.Failure -> {
                        if (
                            result.reason == BoundedHttpsFailureReason.IO &&
                            attempt + 1 < MAX_ATTEMPTS
                        ) {
                            return@repeat
                        }

                        if (result.reason == BoundedHttpsFailureReason.CANCELLED) {
                            healthTracker.recordNeutral(healthPermit)
                        } else {
                            healthTracker.recordHealthFailure(healthPermit)
                        }
                        healthRecorded = true
                        return request.failure(result.reason.toGatewayFailure())
                    }
                }
            }

            error("Provider gateway attempt loop exhausted unexpectedly")
        } catch (cancelled: CancellationException) {
            if (!healthRecorded) {
                healthTracker.recordNeutral(healthPermit)
            }
            throw cancelled
        } catch (error: Throwable) {
            if (!healthRecorded) {
                healthTracker.recordNeutral(healthPermit)
            }
            throw error
        }
    }

    private suspend fun executeOnce(request: BoundedHttpsRequest): BoundedHttpsResult =
        withContext(ioDispatcher) {
            suspendCancellableCoroutine { continuation ->
                val call = transport.newCall(request)
                continuation.invokeOnCancellation {
                    call.cancel()
                }
                val result = call.execute()
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }

    companion object {
        fun production(
            secretSource: ProviderSecretSource = ProviderSecretSource.NONE,
        ): ServerProviderGateway =
            ServerProviderGateway(
                transport = DefaultBoundedHttpsTransport(),
                secretSource = secretSource,
                pacer = processProviderRequestPacer,
                healthTracker = processProviderHealthTracker,
            )
    }
}

private fun ProviderGatewayRequest.failure(
    reason: ProviderGatewayFailureReason,
): ProviderGatewayResult.Failure =
    ProviderGatewayResult.Failure(
        provider = provider,
        modelFamily = modelFamily,
        reason = reason,
    )

private fun BoundedHttpsFailureReason.toGatewayFailure(): ProviderGatewayFailureReason =
    when (this) {
        BoundedHttpsFailureReason.CANCELLED -> ProviderGatewayFailureReason.CANCELLED
        BoundedHttpsFailureReason.IO -> ProviderGatewayFailureReason.IO
        BoundedHttpsFailureReason.RESPONSE_TOO_LARGE ->
            ProviderGatewayFailureReason.RESPONSE_TOO_LARGE
        BoundedHttpsFailureReason.INVALID_RESPONSE ->
            ProviderGatewayFailureReason.INVALID_RESPONSE
    }
