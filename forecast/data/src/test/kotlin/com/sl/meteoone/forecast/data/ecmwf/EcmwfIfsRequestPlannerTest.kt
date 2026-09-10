package com.sl.meteoone.forecast.data.ecmwf

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EcmwfIfsRequestPlannerTest {
    @Test
    fun plansCurrentOperPathForAllOperationalCycles() {
        for (cycle in listOf("00", "06", "12", "18")) {
            val modelRun = Instant.parse("2026-09-10T$cycle:00:00Z")
            val plan = EcmwfIfsRequestPlanner.plan(modelRun, 24)

            val expectedPrefix =
                "https://data.ecmwf.int/forecasts/20260910/${cycle}z/ifs/0p25/oper/" +
                    "20260910${cycle}0000-24h-oper-fc"
            assertEquals("$expectedPrefix.index", plan.indexRequest.uri.toString())
            assertEquals("$expectedPrefix.grib2", plan.gribUri.toString())
            assertEquals(ForecastProvider.ECMWF_OPEN_DATA, plan.provider)
            assertEquals(ModelFamily.ECMWF_IFS, plan.modelFamily)
            assertEquals(modelRun.plusSeconds(24 * 3600L), plan.validTime)
            assertTrue(plan.indexRequest.maxResponseBytes <= 2L * 1024L * 1024L)
        }
    }

    @Test
    fun acceptsThreeHourlyStepsThroughM1Horizon() {
        val modelRun = Instant.parse("2026-09-10T00:00:00Z")

        for (forecastHour in 0..72 step 3) {
            assertEquals(
                forecastHour,
                EcmwfIfsRequestPlanner.plan(modelRun, forecastHour).forecastHour,
            )
        }
    }

    @Test
    fun rejectsNonThreeHourlyStepsAndOutOfRangeHours() {
        val modelRun = Instant.parse("2026-09-10T00:00:00Z")

        assertFailsWith<IllegalArgumentException> {
            EcmwfIfsRequestPlanner.plan(modelRun, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            EcmwfIfsRequestPlanner.plan(modelRun, 73)
        }
    }

    @Test
    fun rejectsInvalidOrUnalignedCycles() {
        assertFailsWith<IllegalArgumentException> {
            EcmwfIfsRequestPlanner.plan(
                Instant.parse("2026-09-10T03:00:00Z"),
                3,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EcmwfIfsRequestPlanner.plan(
                Instant.parse("2026-09-10T06:30:00Z"),
                3,
            )
        }
    }
}
