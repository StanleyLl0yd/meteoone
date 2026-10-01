package com.sl.meteoone.forecast.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ForecastOfficialRunPolicyTest {
    @Test
    fun selectsLatestSixHourlyRunBehindPublicationGuard() {
        assertEquals(
            Instant.parse("2026-09-25T00:00:00Z"),
            ForecastOfficialRunPolicy.selectModelRun(
                Instant.parse("2026-09-25T12:15:00Z"),
            ),
        )
        assertEquals(
            Instant.parse("2026-09-25T06:00:00Z"),
            ForecastOfficialRunPolicy.selectModelRun(
                Instant.parse("2026-09-25T13:00:00Z"),
            ),
        )
    }

    @Test
    fun roundsSubsecondElapsedTimeUpToSupportedForecastSteps() {
        val modelRun = Instant.parse("2026-09-14T00:00:00Z")

        assertEquals(
            13,
            ForecastOfficialRunPolicy.hourlyForecastHour(
                modelRun,
                Instant.parse("2026-09-14T13:00:00Z"),
            ),
        )
        assertEquals(
            14,
            ForecastOfficialRunPolicy.hourlyForecastHour(
                modelRun,
                Instant.parse("2026-09-14T13:00:00.000000001Z"),
            ),
        )
        assertEquals(
            15,
            ForecastOfficialRunPolicy.ecmwfForecastHour(
                modelRun,
                Instant.parse("2026-09-14T15:00:00Z"),
            ),
        )
        assertEquals(
            18,
            ForecastOfficialRunPolicy.ecmwfForecastHour(
                modelRun,
                Instant.parse("2026-09-14T15:00:00.000000001Z"),
            ),
        )
    }
}
