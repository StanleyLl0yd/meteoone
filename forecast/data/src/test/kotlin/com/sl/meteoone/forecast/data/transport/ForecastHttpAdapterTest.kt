package com.sl.meteoone.forecast.data.transport

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsFailureReason
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResponse
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import com.sl.meteoone.forecast.data.ecmwf.EcmwfFieldRangePlan
import com.sl.meteoone.forecast.data.ecmwf.EcmwfIfsRequestPlanner
import com.sl.meteoone.forecast.data.ecmwf.EcmwfSurfaceField
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoForecastRequestPlanner
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoModel
import com.sl.meteoone.forecast.data.source.ByteRange
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ForecastHttpAdapterTest {
    @Test
    fun ordinaryOfficialAndOpenMeteoRequestsRequireHttp200() {
        val transport = RecordingTransport(success(statusCode = 200, body = "official"))
        val adapter = ForecastHttpAdapter(transport)
        val official = OfficialSourceRequest(
            uri = URI.create("https://example.com/data"),
            maxResponseBytes = 1024,
        )

        assertIs<BoundedHttpsResult.Success>(adapter.newOrdinaryCall(official).execute())
        assertEquals(official.uri, transport.lastRequest?.uri)
        assertEquals(official.maxResponseBytes, transport.lastRequest?.maxResponseBytes)

        for (status in listOf(201, 206, 302, 404)) {
            transport.result = success(statusCode = status, body = "unexpected")
            assertInvalid(adapter.newOrdinaryCall(official).execute())
        }

        val openMeteo = OpenMeteoForecastRequestPlanner.plan(
            model = OpenMeteoModel.NOAA_GFS_GLOBAL,
            coordinate = ForecastCoordinate(latitude = 59.9, longitude = 30.3),
            generatedAt = Instant.parse("2026-09-10T12:30:00Z"),
        )
        transport.result = success(statusCode = 200, body = "{}")
        assertIs<BoundedHttpsResult.Success>(adapter.newOrdinaryCall(openMeteo).execute())
        assertEquals(openMeteo.uri, transport.lastRequest?.uri)
        assertEquals(openMeteo.maxResponseBytes, transport.lastRequest?.maxResponseBytes)

        transport.result = success(statusCode = 302, body = "redirect")
        assertInvalid(adapter.newOrdinaryCall(openMeteo).execute())
    }

    @Test
    fun ecmwfRangeRequestUsesExactRangeAndValidatesPartialResponse() {
        val plan = ecmwfRangePlan()
        val transport = RecordingTransport(
            success(
                statusCode = 206,
                body = "abc",
                headers = mapOf("Content-Range" to listOf("bytes 100-102/1000")),
            ),
        )
        val adapter = ForecastHttpAdapter(transport)

        assertIs<BoundedHttpsResult.Success>(adapter.newFieldRangeCall(plan).execute())
        val request = requireNotNull(transport.lastRequest)
        assertEquals(plan.request.uri, request.uri)
        assertEquals(plan.range.length, request.maxResponseBytes)
        assertEquals("bytes=100-102", request.headers["Range"])
        assertEquals("identity", request.headers["Accept-Encoding"])
    }

    @Test
    fun ecmwfRangeResponseFailsClosedOnStatusHeaderRangeTotalOrLengthMismatch() {
        val plan = ecmwfRangePlan()
        val cases = listOf(
            success(
                statusCode = 200,
                body = "abc",
                headers = mapOf("Content-Range" to listOf("bytes 100-102/1000")),
            ),
            success(statusCode = 206, body = "abc"),
            success(
                statusCode = 206,
                body = "abc",
                headers = mapOf("Content-Range" to listOf("bytes nope")),
            ),
            success(
                statusCode = 206,
                body = "abc",
                headers = mapOf("Content-Range" to listOf("bytes 99-101/1000")),
            ),
            success(
                statusCode = 206,
                body = "abc",
                headers = mapOf(
                    "Content-Range" to listOf(
                        "bytes 100-102/1000",
                        "bytes 100-102/1000",
                    ),
                ),
            ),
            success(
                statusCode = 206,
                body = "abc",
                headers = mapOf(
                    "Content-Range" to listOf("bytes 100-102/1000"),
                    "content-range" to listOf("bytes 100-102/1000"),
                ),
            ),
            success(
                statusCode = 206,
                body = "abc",
                headers = mapOf("Content-Range" to listOf("bytes 100-102/102")),
            ),
            success(
                statusCode = 206,
                body = "ab",
                headers = mapOf("Content-Range" to listOf("bytes 100-102/1000")),
            ),
        )

        for (result in cases) {
            val transport = RecordingTransport(result)
            assertInvalid(ForecastHttpAdapter(transport).newFieldRangeCall(plan).execute())
        }
    }

    @Test
    fun ecmwfRangeResponseAcceptsUnknownCompleteLengthWhenSelectedRangeIsExact() {
        val plan = ecmwfRangePlan()
        val transport = RecordingTransport(
            success(
                statusCode = 206,
                body = "abc",
                headers = mapOf("Content-Range" to listOf("bytes 100-102/*")),
            ),
        )

        assertIs<BoundedHttpsResult.Success>(
            ForecastHttpAdapter(transport).newFieldRangeCall(plan).execute(),
        )
    }

    @Test
    fun transportFailureAndCancellationRemainTypedAndDelegated() {
        val official = OfficialSourceRequest(
            uri = URI.create("https://example.com/data"),
            maxResponseBytes = 1024,
        )
        val transport = RecordingTransport(
            BoundedHttpsResult.Failure(BoundedHttpsFailureReason.RESPONSE_TOO_LARGE),
        )
        val call = ForecastHttpAdapter(transport).newOrdinaryCall(official)

        assertEquals(
            BoundedHttpsResult.Failure(BoundedHttpsFailureReason.RESPONSE_TOO_LARGE),
            call.execute(),
        )
        call.cancel()
        assertTrue(requireNotNull(transport.lastCall).cancelled)
    }

    private fun ecmwfRangePlan(): EcmwfFieldRangePlan {
        val base = EcmwfIfsRequestPlanner.plan(
            modelRun = Instant.parse("2026-09-10T06:00:00Z"),
            forecastHour = 6,
        )
        val range = ByteRange(offset = 100, length = 3)
        return EcmwfFieldRangePlan(
            request = OfficialSourceRequest(
                uri = base.gribUri,
                maxResponseBytes = range.length,
            ),
            range = range,
            field = EcmwfSurfaceField.TEMPERATURE_2M,
            provider = ForecastProvider.ECMWF_OPEN_DATA,
            modelFamily = ModelFamily.ECMWF_IFS,
            modelRun = base.modelRun,
            validTime = base.validTime,
            forecastHour = base.forecastHour,
        )
    }

    private fun assertInvalid(result: BoundedHttpsResult) {
        assertEquals(
            BoundedHttpsFailureReason.INVALID_RESPONSE,
            assertIs<BoundedHttpsResult.Failure>(result).reason,
        )
    }

    private class RecordingTransport(
        var result: BoundedHttpsResult,
    ) : BoundedHttpsTransport {
        var lastRequest: BoundedHttpsRequest? = null
            private set
        var lastCall: RecordingCall? = null
            private set

        override fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall {
            lastRequest = request
            return RecordingCall(result).also { lastCall = it }
        }
    }

    private class RecordingCall(
        private val result: BoundedHttpsResult,
    ) : BoundedHttpsCall {
        var cancelled = false
            private set

        override fun execute(): BoundedHttpsResult = result

        override fun cancel() {
            cancelled = true
        }
    }

    private companion object {
        fun success(
            statusCode: Int,
            body: String,
            headers: Map<String, List<String>> = emptyMap(),
        ): BoundedHttpsResult.Success = BoundedHttpsResult.Success(
            BoundedHttpsResponse(
                statusCode = statusCode,
                headers = headers,
                body = body.encodeToByteArray(),
            ),
        )
    }
}
