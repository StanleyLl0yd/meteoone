package com.sl.meteoone.forecast.data.execution

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class M1OfficialRunPolicyTest {
    @Test
    fun roundsSubsecondElapsedTimeUpToTheNextSupportedForecastStep() {
        val modelRun = Instant.parse("2026-09-14T00:00:00Z")

        assertEquals(
            13,
            M1OfficialRunPolicy.hourlyForecastHour(
                modelRun,
                Instant.parse("2026-09-14T13:00:00Z"),
            ),
        )
        assertEquals(
            14,
            M1OfficialRunPolicy.hourlyForecastHour(
                modelRun,
                Instant.parse("2026-09-14T13:00:00.000000001Z"),
            ),
        )
        assertEquals(
            15,
            M1OfficialRunPolicy.ecmwfForecastHour(
                modelRun,
                Instant.parse("2026-09-14T15:00:00Z"),
            ),
        )
        assertEquals(
            18,
            M1OfficialRunPolicy.ecmwfForecastHour(
                modelRun,
                Instant.parse("2026-09-14T15:00:00.000000001Z"),
            ),
        )
    }
}
