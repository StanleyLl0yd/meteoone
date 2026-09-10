package com.sl.meteoone.forecast.data.dwd

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DwdIconGridGeometryPlannerTest {
    @Test
    fun plansOfficialLatitudeAndLongitudeGeometry() {
        val modelRun = Instant.parse("2026-09-10T00:00:00Z")
        val plan = DwdIconGridGeometryPlanner.plan(modelRun)

        assertEquals(
            "https://opendata.dwd.de/weather/nwp/icon/grib/00/clat/" +
                "icon_global_icosahedral_time-invariant_2026091000_CLAT.grib2.bz2",
            plan.latitudeRequest.uri.toString(),
        )
        assertEquals(
            "https://opendata.dwd.de/weather/nwp/icon/grib/00/clon/" +
                "icon_global_icosahedral_time-invariant_2026091000_CLON.grib2.bz2",
            plan.longitudeRequest.uri.toString(),
        )
        assertEquals(modelRun, plan.modelRun)
        assertTrue(plan.latitudeRequest.maxResponseBytes <= 4L * 1024L * 1024L)
        assertTrue(plan.longitudeRequest.maxResponseBytes <= 4L * 1024L * 1024L)
    }

    @Test
    fun rejectsInvalidCycle() {
        assertFailsWith<IllegalArgumentException> {
            DwdIconGridGeometryPlanner.plan(
                Instant.parse("2026-09-10T09:00:00Z"),
            )
        }
    }
}
