package com.sl.meteoone.forecast.data.transport

import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsFailureReason
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResponse
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import com.sl.meteoone.forecast.data.ecmwf.EcmwfFieldRangePlan
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoForecastRequest
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest

private val CONTENT_RANGE = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)$")

internal class ForecastHttpAdapter(
    private val transport: BoundedHttpsTransport,
) {
    fun newOrdinaryCall(request: OfficialSourceRequest): BoundedHttpsCall =
        validatingCall(
            request = BoundedHttpsRequest(
                uri = request.uri,
                maxResponseBytes = request.maxResponseBytes,
            ),
            validator = ::validateOrdinaryResponse,
        )

    fun newOrdinaryCall(request: OpenMeteoForecastRequest): BoundedHttpsCall =
        validatingCall(
            request = BoundedHttpsRequest(
                uri = request.uri,
                maxResponseBytes = request.maxResponseBytes,
            ),
            validator = ::validateOrdinaryResponse,
        )

    fun newFieldRangeCall(plan: EcmwfFieldRangePlan): BoundedHttpsCall =
        validatingCall(
            request = BoundedHttpsRequest(
                uri = plan.request.uri,
                maxResponseBytes = plan.request.maxResponseBytes,
                headers = mapOf(
                    "Range" to plan.range.headerValue,
                    "Accept-Encoding" to "identity",
                ),
            ),
        ) { response -> validateEcmwfRangeResponse(response, plan) }

    private fun validatingCall(
        request: BoundedHttpsRequest,
        validator: (BoundedHttpsResponse) -> BoundedHttpsResult,
    ): BoundedHttpsCall = ValidatingBoundedHttpsCall(
        delegate = transport.newCall(request),
        validator = validator,
    )
}

private class ValidatingBoundedHttpsCall(
    private val delegate: BoundedHttpsCall,
    private val validator: (BoundedHttpsResponse) -> BoundedHttpsResult,
) : BoundedHttpsCall {
    override fun execute(): BoundedHttpsResult = when (val result = delegate.execute()) {
        is BoundedHttpsResult.Failure -> result
        is BoundedHttpsResult.Success -> validator(result.response)
    }

    override fun cancel() {
        delegate.cancel()
    }
}

private fun validateOrdinaryResponse(response: BoundedHttpsResponse): BoundedHttpsResult =
    if (response.statusCode == 200) {
        BoundedHttpsResult.Success(response)
    } else {
        invalidResponse()
    }

private fun validateEcmwfRangeResponse(
    response: BoundedHttpsResponse,
    plan: EcmwfFieldRangePlan,
): BoundedHttpsResult {
    if (response.statusCode != 206) return invalidResponse()
    if (response.body.size.toLong() != plan.range.length) return invalidResponse()

    val contentRanges = response.headerValues("Content-Range")
    if (contentRanges.size != 1) return invalidResponse()

    val match = CONTENT_RANGE.matchEntire(contentRanges.single()) ?: return invalidResponse()
    val start = match.groupValues[1].toLongOrNull() ?: return invalidResponse()
    val end = match.groupValues[2].toLongOrNull() ?: return invalidResponse()
    if (start != plan.range.offset || end != plan.range.inclusiveEnd) return invalidResponse()

    val totalToken = match.groupValues[3]
    if (totalToken != "*") {
        val total = totalToken.toLongOrNull() ?: return invalidResponse()
        if (total <= end) return invalidResponse()
    }

    return BoundedHttpsResult.Success(response)
}

private fun invalidResponse(): BoundedHttpsResult.Failure =
    BoundedHttpsResult.Failure(BoundedHttpsFailureReason.INVALID_RESPONSE)
