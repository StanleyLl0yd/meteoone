package com.sl.meteoone.core.network

import java.net.URI
import java.util.Locale

private const val MAX_NETWORK_RESPONSE_BYTES = 32L * 1024L * 1024L
private val HTTP_HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

class BoundedHttpsRequest(
    val uri: URI,
    val maxResponseBytes: Long,
    headers: Map<String, String> = emptyMap(),
) {
    val headers: Map<String, String> = LinkedHashMap(headers)

    init {
        require(uri.isAbsolute) { "Network request URI must be absolute" }
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Network requests must use HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "Network request host is required" }
        require(uri.userInfo == null) { "Network requests must not contain user info" }
        require(uri.port == -1 || uri.port == 443) {
            "Network requests must use the default HTTPS port"
        }
        require(uri.fragment == null) { "Network requests must not contain fragments" }
        require(maxResponseBytes in 1..MAX_NETWORK_RESPONSE_BYTES) {
            "Network response byte limit is out of bounds"
        }

        val normalizedNames = HashSet<String>()
        this.headers.forEach { (name, value) ->
            require(HTTP_HEADER_NAME.matches(name)) { "Invalid HTTP header name" }
            require(normalizedNames.add(name.lowercase(Locale.ROOT))) {
                "Duplicate HTTP header name"
            }
            require(value.none { character -> character.code < 0x20 || character.code == 0x7f }) {
                "HTTP header values must not contain control characters"
            }
            if (name.equals("Accept-Encoding", ignoreCase = true)) {
                require(value.equals("identity", ignoreCase = true)) {
                    "Accept-Encoding may only be identity"
                }
            }
        }
    }
}

class BoundedHttpsResponse(
    val statusCode: Int,
    headers: Map<String, List<String>>,
    body: ByteArray,
) {
    val headers: Map<String, List<String>> = headers.mapValues { (_, values) -> values.toList() }
    val body: ByteArray = body.copyOf()

    fun headerValues(name: String): List<String> =
        headers.entries
            .firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
            ?.value
            .orEmpty()
}

enum class BoundedHttpsFailureReason {
    CANCELLED,
    IO,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
}

sealed interface BoundedHttpsResult {
    data class Success(val response: BoundedHttpsResponse) : BoundedHttpsResult

    data class Failure(val reason: BoundedHttpsFailureReason) : BoundedHttpsResult
}

interface BoundedHttpsCall {
    fun execute(): BoundedHttpsResult

    fun cancel()
}

fun interface BoundedHttpsTransport {
    fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall
}
