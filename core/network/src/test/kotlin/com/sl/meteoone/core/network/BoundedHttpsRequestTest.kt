package com.sl.meteoone.core.network

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedHttpsRequestTest {
    @Test
    fun acceptsBoundedHttpsRequestAndDefensivelyCopiesHeaders() {
        val mutable = linkedMapOf("Range" to "bytes=10-19")
        val request = BoundedHttpsRequest(
            uri = URI.create("https://example.com/data"),
            maxResponseBytes = 10,
            headers = mutable,
        )
        mutable["Range"] = "bytes=20-29"

        assertEquals("bytes=10-19", request.headers["Range"])
    }

    @Test
    fun rejectsUnsafeUriAndBounds() {
        listOf(
            "http://example.com/data",
            "https://user@example.com/data",
            "https://example.com:8443/data",
            "https://example.com/data#fragment",
        ).forEach { uri ->
            assertFailsWith<IllegalArgumentException> {
                BoundedHttpsRequest(URI.create(uri), maxResponseBytes = 1)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            BoundedHttpsRequest(URI.create("https://example.com/data"), maxResponseBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BoundedHttpsRequest(
                URI.create("https://example.com/data"),
                maxResponseBytes = 32L * 1024L * 1024L + 1,
            )
        }
    }

    @Test
    fun rejectsUnsafeHeadersAndNonIdentityEncoding() {
        assertFailsWith<IllegalArgumentException> {
            BoundedHttpsRequest(
                URI.create("https://example.com/data"),
                10,
                mapOf("Bad Header" to "value"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BoundedHttpsRequest(
                URI.create("https://example.com/data"),
                10,
                mapOf("X-Test" to "value\r\ninjected: yes"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BoundedHttpsRequest(
                URI.create("https://example.com/data"),
                10,
                linkedMapOf("Range" to "bytes=0-9", "range" to "bytes=0-9"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BoundedHttpsRequest(
                URI.create("https://example.com/data"),
                10,
                mapOf("Accept-Encoding" to "gzip"),
            )
        }
    }
}
