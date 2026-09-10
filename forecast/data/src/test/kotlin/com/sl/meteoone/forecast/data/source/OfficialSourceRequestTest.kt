package com.sl.meteoone.forecast.data.source

import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

class OfficialSourceRequestTest {
    @Test
    fun rejectsCleartextTransport() {
        assertFailsWith<IllegalArgumentException> {
            OfficialSourceRequest(
                uri = URI.create("http://example.test/forecast"),
                maxResponseBytes = 1024,
            )
        }
    }

    @Test
    fun rejectsMissingOrExcessiveResponseBounds() {
        assertFailsWith<IllegalArgumentException> {
            OfficialSourceRequest(
                uri = URI.create("https://example.test/forecast"),
                maxResponseBytes = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OfficialSourceRequest(
                uri = URI.create("https://example.test/forecast"),
                maxResponseBytes = 64L * 1024L * 1024L + 1,
            )
        }
    }

    @Test
    fun rejectsNegativeRequestSpacing() {
        assertFailsWith<IllegalArgumentException> {
            OfficialSourceRequest(
                uri = URI.create("https://example.test/forecast"),
                maxResponseBytes = 1024,
                minimumRequestSpacing = Duration.ofSeconds(-1),
            )
        }
    }
}
