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
    private val healthPolicy: ProviderHealthPolicy,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProviderGateway {
    override suspend fun execute(
        request: ProviderGatewayRequest,
        responseValidator: ProviderResponseValidator,
    ): ProviderGatewayResult {
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

        val permit = healthPolicy.tryAcquire(
            provider = request.provider,
            host = request.uri.host,
        ) ?: return request.failure(ProviderGatewayFailureReason.CIRCUIT_OPEN)

        return try {
            val result = executeWithRetry(
                request = request,
                headers = headers,
                responseValidator = responseValidator,
            )
            when (result) {
                is ProviderGatewayResult.Success -> healthPolicy.recordSuccess(permit)
                is ProviderGatewayResult.Failure ->
                    healthPolicy.recordFailure(permit, result.reason)
            }
            result
        } catch (cancellation: CancellationException) {
            healthPolicy.recordFailure(
                permit = permit,
                reason = ProviderGatewayFailureReason.CANCELLED,
            )
            throw cancellation
        } catch (error: Throwable) {
            healthPolicy.recordFailure(
                permit = permit,
                reason = ProviderGatewayFailureReason.CANCELLED,
            )
            throw error
        }
    }

    private suspend fun executeWithRetry(
        request: ProviderGatewayRequest,
        headers: Map<String, String>,
        responseValidator: ProviderResponseValidator,
    ): ProviderGatewayResult {
        val networkRequest = BoundedHttpsRequest(
            uri = request.uri,
            maxResponseBytes = request.maxResponseBytes,
            headers = headers,
        )

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
                        return request.failure(ProviderGatewayFailureReason.INVALID_RESPONSE)
                    }
                    val gatewayResponse = ProviderGatewayResponse(
                        provider = request.provider,
                        modelFamily = request.modelFamily,
                        statusCode = response.statusCode,
                        headers = response.headers,
                        body = response.body,
                    )
                    if (!responseValidator.isValid(gatewayResponse)) {
                        return request.failure(ProviderGatewayFailureReason.INVALID_RESPONSE)
                    }
                    return ProviderGatewayResult.Success(gatewayResponse)
                }

                is BoundedHttpsResult.Failure -> {
                    if (result.reason == BoundedHttpsFailureReason.IO && attempt + 1 < MAX_ATTEMPTS) {
                        return@repeat
                    }
                    return request.failure(result.reason.toGatewayFailure())
                }
            }
        }

        error("Provider gateway attempt loop exhausted unexpectedly")
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
                healthPolicy = processProviderHealthPolicy,
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
