package com.sl.meteoone.backend.provider

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.network.BoundedHttpsRequest
import java.net.URI
import java.time.Duration

private val CREDENTIAL_SLOT = Regex("^[A-Z][A-Z0-9_]{0,63}$")
private val HEADER_NAME = Regex("^[A-Za-z][A-Za-z0-9-]{0,63}$")
private val MAX_REQUEST_SPACING: Duration = Duration.ofMinutes(10)

@JvmInline
value class ProviderCredentialSlot(
    val value: String,
) {
    init {
        require(CREDENTIAL_SLOT.matches(value)) {
            "Provider credential slot must use a stable uppercase identifier"
        }
    }
}

data class ProviderCredentialRequirement(
    val slot: ProviderCredentialSlot,
    val headerName: String,
    val valuePrefix: String = "",
) {
    init {
        require(HEADER_NAME.matches(headerName)) {
            "Provider credential header name is invalid"
        }
        require(valuePrefix.none { character -> character.code < 0x20 || character.code == 0x7f }) {
            "Provider credential header prefix must not contain control characters"
        }
    }
}

fun interface ProviderSecretSource {
    fun resolve(slot: ProviderCredentialSlot): String?

    companion object {
        val NONE: ProviderSecretSource = ProviderSecretSource { null }
    }
}

data class ProviderGatewayRequest(
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val uri: URI,
    val maxResponseBytes: Long,
    val expectedStatusCodes: Set<Int> = setOf(200),
    val minimumRequestSpacing: Duration = Duration.ZERO,
    val credential: ProviderCredentialRequirement? = null,
) {
    init {
        require(provider != ForecastProvider.UNKNOWN) {
            "Provider gateway request must use a known provider"
        }
        require(modelFamily != ModelFamily.UNKNOWN) {
            "Provider gateway request must use a known model family"
        }
        require(expectedStatusCodes.isNotEmpty() && expectedStatusCodes.all { it in 100..599 }) {
            "Provider expected HTTP status codes must be non-empty valid status codes"
        }
        require(!minimumRequestSpacing.isNegative && minimumRequestSpacing <= MAX_REQUEST_SPACING) {
            "Provider request spacing must be between zero and $MAX_REQUEST_SPACING"
        }
        BoundedHttpsRequest(
            uri = uri,
            maxResponseBytes = maxResponseBytes,
        )
    }
}

class ProviderGatewayResponse(
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val statusCode: Int,
    headers: Map<String, List<String>>,
    body: ByteArray,
) {
    val headers: Map<String, List<String>> = headers.mapValues { (_, values) -> values.toList() }
    val body: ByteArray = body.copyOf()
}

enum class ProviderGatewayFailureReason {
    MISSING_CREDENTIAL,
    CIRCUIT_OPEN,
    CANCELLED,
    IO,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
}

fun interface ProviderResponseValidator {
    fun isValid(response: ProviderGatewayResponse): Boolean

    companion object {
        val ACCEPT_ALL: ProviderResponseValidator = ProviderResponseValidator { true }
    }
}

interface ProviderGateway {
    suspend fun execute(
        request: ProviderGatewayRequest,
        responseValidator: ProviderResponseValidator = ProviderResponseValidator.ACCEPT_ALL,
    ): ProviderGatewayResult
}

sealed interface ProviderGatewayResult {
    data class Success(
        val response: ProviderGatewayResponse,
    ) : ProviderGatewayResult

    data class Failure(
        val provider: ForecastProvider,
        val modelFamily: ModelFamily,
        val reason: ProviderGatewayFailureReason,
    ) : ProviderGatewayResult
}
