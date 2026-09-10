package com.sl.meteoone.forecast.data.source

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun plannedForecastRequestRequiresExactForecastHourValidTime() {
        val modelRun = Instant.parse("2026-09-10T06:00:00Z")
        val exact = plannedForecastRequest(
            modelRun = modelRun,
            validTime = modelRun.plusSeconds(6 * 3600),
            forecastHour = 6,
        )
        assertEquals(6, exact.forecastHour)

        assertFailsWith<IllegalArgumentException> {
            plannedForecastRequest(
                modelRun = modelRun,
                validTime = modelRun.plusSeconds(5 * 3600),
                forecastHour = 6,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            plannedForecastRequest(
                modelRun = modelRun,
                validTime = modelRun.plusSeconds(7 * 3600),
                forecastHour = 6,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            plannedForecastRequest(
                modelRun = modelRun,
                validTime = modelRun.minusSeconds(3600),
                forecastHour = -1,
            )
        }
    }

    private fun plannedForecastRequest(
        modelRun: Instant,
        validTime: Instant,
        forecastHour: Int,
    ) = PlannedForecastRequest(
        request = OfficialSourceRequest(
            uri = URI.create("https://example.test/forecast"),
            maxResponseBytes = 1024,
        ),
        provider = ForecastProvider.NOAA_NOMADS,
        modelFamily = ModelFamily.NOAA_GFS,
        modelRun = modelRun,
        validTime = validTime,
        forecastHour = forecastHour,
        gridPoint = SourceGridPoint(
            latitude = 59.9,
            longitudeDegreesEast = 30.3,
        ),
    )
}
