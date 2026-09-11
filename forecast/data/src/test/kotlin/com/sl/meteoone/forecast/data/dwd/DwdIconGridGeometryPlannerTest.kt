package com.sl.meteoone.forecast.data.dwd

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        assertEquals(4L * 1024L * 1024L, plan.latitudeRequest.maxResponseBytes)
        assertEquals(4L * 1024L * 1024L, plan.longitudeRequest.maxResponseBytes)
    }

    @Test
    fun rejectsForeignGeometryHost() {
        val plan = validPlan()
        val foreignLatitude = plan.latitudeRequest.copy(
            uri = URI.create(
                plan.latitudeRequest.uri.toString().replace(
                    "opendata.dwd.de",
                    "example.com",
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            plan.copy(latitudeRequest = foreignLatitude)
        }
    }

    @Test
    fun rejectsWrongRunToken() {
        val plan = validPlan()
        val wrongRunLongitude = plan.longitudeRequest.copy(
            uri = URI.create(
                plan.longitudeRequest.uri.toString().replace(
                    "2026091000_CLON",
                    "2026091006_CLON",
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            plan.copy(longitudeRequest = wrongRunLongitude)
        }
    }

    @Test
    fun rejectsSwappedLatitudeAndLongitudeRequests() {
        val plan = validPlan()

        assertFailsWith<IllegalArgumentException> {
            plan.copy(
                latitudeRequest = plan.longitudeRequest,
                longitudeRequest = plan.latitudeRequest,
            )
        }
    }

    @Test
    fun rejectsGeometryResponseBoundDrift() {
        val plan = validPlan()
        val unboundedLatitude = plan.latitudeRequest.copy(
            maxResponseBytes = plan.latitudeRequest.maxResponseBytes + 1,
        )

        assertFailsWith<IllegalArgumentException> {
            plan.copy(latitudeRequest = unboundedLatitude)
        }
    }

    @Test
    fun rejectsInvalidCycleAtPlanBoundary() {
        val plan = validPlan()

        assertFailsWith<IllegalArgumentException> {
            plan.copy(modelRun = Instant.parse("2026-09-10T09:00:00Z"))
        }
    }

    @Test
    fun rejectsMisalignedModelRunAtPlanBoundary() {
        val plan = validPlan()

        assertFailsWith<IllegalArgumentException> {
            plan.copy(modelRun = Instant.parse("2026-09-10T06:00:01Z"))
        }
    }

    private fun validPlan(): DwdIconGridGeometryPlan =
        DwdIconGridGeometryPlanner.plan(
            Instant.parse("2026-09-10T00:00:00Z"),
        )
}
