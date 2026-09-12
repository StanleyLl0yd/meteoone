package com.sl.meteoone.core.network

import java.io.IOException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout

class DefaultBoundedHttpsTransportTest {
    @Test
    fun productionClientDisablesRedirectsRetriesAndCookies() {
        val client = DefaultBoundedHttpsTransport.createClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(45_000, client.callTimeoutMillis)
    }

    @Test
    fun addsIdentityEncodingAndPreservesExplicitHeaders() {
        val factory = FakeCallFactory { request -> response(request, 200, "abc".toResponseBody()) }
        val call = DefaultBoundedHttpsTransport(factory).newCall(
            BoundedHttpsRequest(
                uri = URI.create("https://example.com/data"),
                maxResponseBytes = 10,
                headers = mapOf("Range" to "bytes=1-3"),
            ),
        )

        val result = assertIs<BoundedHttpsResult.Success>(call.execute())
        assertTrue(result.response.body.contentEquals("abc".encodeToByteArray()))
        assertEquals("identity", factory.lastRequest?.header("Accept-Encoding"))
        assertEquals("bytes=1-3", factory.lastRequest?.header("Range"))
    }

    @Test
    fun rejectsDeclaredOrStreamedBodyAboveBound() {
        val declaredFactory = FakeCallFactory { request ->
            response(
                request = request,
                code = 200,
                body = "0123456789".toResponseBody(),
            )
        }
        val declared = DefaultBoundedHttpsTransport(declaredFactory).newCall(
            BoundedHttpsRequest(URI.create("https://example.com/data"), 5),
        )
        assertEquals(
            BoundedHttpsFailureReason.RESPONSE_TOO_LARGE,
            assertIs<BoundedHttpsResult.Failure>(declared.execute()).reason,
        )

        val streamedFactory = FakeCallFactory { request ->
            response(request, 200, UnknownLengthBody("0123456789".encodeToByteArray()))
        }
        val streamed = DefaultBoundedHttpsTransport(streamedFactory).newCall(
            BoundedHttpsRequest(URI.create("https://example.com/data"), 5),
        )
        assertEquals(
            BoundedHttpsFailureReason.RESPONSE_TOO_LARGE,
            assertIs<BoundedHttpsResult.Failure>(streamed.execute()).reason,
        )
    }

    @Test
    fun rejectsConflictingLengthAndUnexpectedContentEncoding() {
        val wrongLengthFactory = FakeCallFactory { request ->
            response(
                request = request,
                code = 200,
                body = UnknownLengthBody("abc".encodeToByteArray()),
                headers = mapOf("Content-Length" to "4"),
            )
        }
        val wrongLength = DefaultBoundedHttpsTransport(wrongLengthFactory).newCall(
            BoundedHttpsRequest(URI.create("https://example.com/data"), 10),
        )
        assertEquals(
            BoundedHttpsFailureReason.INVALID_RESPONSE,
            assertIs<BoundedHttpsResult.Failure>(wrongLength.execute()).reason,
        )

        val encodedFactory = FakeCallFactory { request ->
            response(
                request = request,
                code = 200,
                body = "abc".toResponseBody(),
                headers = mapOf("Content-Encoding" to "gzip"),
            )
        }
        val encoded = DefaultBoundedHttpsTransport(encodedFactory).newCall(
            BoundedHttpsRequest(URI.create("https://example.com/data"), 10),
        )
        assertEquals(
            BoundedHttpsFailureReason.INVALID_RESPONSE,
            assertIs<BoundedHttpsResult.Failure>(encoded.execute()).reason,
        )
    }

    @Test
    fun mapsIoAndCancellationWithoutLeakingException() {
        val ioFactory = FakeCallFactory(throwable = IOException("network details"))
        val ioCall = DefaultBoundedHttpsTransport(ioFactory).newCall(
            BoundedHttpsRequest(URI.create("https://example.com/data"), 10),
        )
        assertEquals(
            BoundedHttpsFailureReason.IO,
            assertIs<BoundedHttpsResult.Failure>(ioCall.execute()).reason,
        )

        val cancelFactory = FakeCallFactory { request -> response(request, 200, "abc".toResponseBody()) }
        val cancelCall = DefaultBoundedHttpsTransport(cancelFactory).newCall(
            BoundedHttpsRequest(URI.create("https://example.com/data"), 10),
        )
        cancelCall.cancel()
        assertEquals(
            BoundedHttpsFailureReason.CANCELLED,
            assertIs<BoundedHttpsResult.Failure>(cancelCall.execute()).reason,
        )
    }

    private class UnknownLengthBody(data: ByteArray) : ResponseBody() {
        private val buffer = Buffer().write(data)

        override fun contentType() = null

        override fun contentLength(): Long = -1

        override fun source(): BufferedSource = buffer
    }

    private class FakeCallFactory(
        private val throwable: IOException? = null,
        private val responseFactory: (Request) -> Response = { error("No response configured") },
    ) : Call.Factory {
        var lastRequest: Request? = null
            private set

        override fun newCall(request: Request): Call {
            lastRequest = request
            return FakeCall(request, throwable, responseFactory)
        }
    }

    private class FakeCall(
        private val requestValue: Request,
        private val throwable: IOException?,
        private val responseFactory: (Request) -> Response,
    ) : Call {
        private var executed = false
        private var cancelled = false

        override fun request(): Request = requestValue

        override fun execute(): Response {
            executed = true
            if (cancelled) throw IOException("cancelled")
            throwable?.let { throw it }
            return responseFactory(requestValue)
        }

        override fun enqueue(responseCallback: Callback) = error("Not used by synchronous transport")

        override fun cancel() {
            cancelled = true
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = cancelled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = FakeCall(requestValue, throwable, responseFactory)
    }

    private companion object {
        fun response(
            request: Request,
            code: Int,
            body: ResponseBody,
            headers: Map<String, String> = emptyMap(),
        ): Response {
            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body)
            headers.forEach { (name, value) -> builder.header(name, value) }
            return builder.build()
        }
    }
}
