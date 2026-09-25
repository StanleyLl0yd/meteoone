package com.sl.meteoone.backend.provider

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.network.BoundedHttpsRequest
import java.net.URI
import java.time.Duration
import java.util.Locale

private val CREDENTIAL_SLOT = Regex("^[A-Z][A-Z0-9_]{0,63}$")
private val HEADER_NAME = Regex("^[A-Za-z][A-Za-z0-9-]{0,63}$")
private val MAX_REQUEST_SPACING: Duration = Duration.ofMinutes(10)
private val RESERVED_TRANSPORT_HEADERS = setOf("range", "accept-encoding")

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
        require(headerName.lowercase(Locale.ROOT) !in RESERVED_TRANSPORT_HEADERS) {
            "Provider credential header is reserved by the transport boundary"
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

data class ProviderByteRange(
    val offset: Long,
    val length: Long,
) {
    init {
        require(offset >= 0L) { "Provider byte-range offset must not be negative" }
        require(length > 0L) { "Provider byte-range length must be positive" }
        require(offset <= Long.MAX_VALUE - (length - 1L)) {
            "Provider byte range exceeds Long address space"
        }
    }

    val inclusiveEnd: Long
        get() = offset + length - 1L

    val headerValue: String
        get() = "bytes=$offset-$inclusiveEnd"
}

data class ProviderGatewayRequest(
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val uri: URI,
    val maxResponseBytes: Long,
    val expectedStatusCodes: Set<Int> = setOf(200),
    val minimumRequestSpacing: Duration = Duration.ZERO,
    val credential: ProviderCredentialRequirement? = null,
    val byteRange: ProviderByteRange? = null,
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
        byteRange?.let { range ->
            require(expectedStatusCodes == setOf(206)) {
                "Provider byte-range requests must require exactly HTTP 206"
            }
            require(maxResponseBytes == range.length) {
                "Provider byte-range response limit must equal the requested range length"
            }
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
