package com.sl.meteoone.forecast.data.noaa

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NoaaGfsRequestPlannerTest {
    @Test
    fun plansBoundedOfficialRequestForSeventyTwoHourForecast() {
        val run = Instant.parse("2026-09-10T06:00:00Z")
        val plan = NoaaGfsRequestPlanner.plan(
            modelRun = run,
            coordinate = coordinate(latitude = 59.9, longitude = 30.3),
            forecastHour = 72,
        )

        assertEquals(ForecastProvider.NOAA_NOMADS, plan.provider)
        assertEquals(ModelFamily.NOAA_GFS, plan.modelFamily)
        assertEquals(run, plan.modelRun)
        assertEquals(Instant.parse("2026-09-13T06:00:00Z"), plan.validTime)
        assertEquals(72, plan.forecastHour)
        assertEquals(60.0, plan.gridPoint.latitude)
        assertEquals(30.25, plan.gridPoint.longitudeDegreesEast)

        val uri = plan.request.uri
        assertEquals("https", uri.scheme)
        assertEquals("nomads.ncep.noaa.gov", uri.host)
        assertEquals("/cgi-bin/filter_gfs_0p25.pl", uri.path)
        assertEquals(2L * 1024L * 1024L, plan.request.maxResponseBytes)
        assertEquals(Duration.ofSeconds(10), plan.request.minimumRequestSpacing)

        val query = uri.rawQuery
        assertTrue(query.contains("file=gfs.t06z.pgrb2.0p25.f072"))
        assertTrue(query.contains("var_TMP=on"))
        assertTrue(query.contains("var_PRMSL=on"))
        assertTrue(query.contains("var_UGRD=on"))
        assertTrue(query.contains("var_VGRD=on"))
        assertTrue(query.contains("var_APCP=on"))
        assertTrue(query.contains("lev_2_m_above_ground=on"))
        assertTrue(query.contains("lev_10_m_above_ground=on"))
        assertTrue(query.contains("lev_mean_sea_level=on"))
        assertTrue(query.contains("leftlon=30.25&rightlon=30.25&toplat=60&bottomlat=60"))
        assertTrue(query.endsWith("dir=%2Fgfs.20260910%2F06%2Fatmos"))
    }

    @Test
    fun requestPlanRejectsUriAndTransportDrift() {
        val run = Instant.parse("2026-09-10T06:00:00Z")
        val plan = NoaaGfsRequestPlanner.plan(
            modelRun = run,
            coordinate = coordinate(latitude = 59.9, longitude = 30.3),
            forecastHour = 6,
        )

        assertFailsWith<IllegalArgumentException> {
            plan.copy(
                request = plan.request.copy(
                    uri = URI.create("https://example.com/cgi-bin/filter_gfs_0p25.pl"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            plan.copy(
                request = plan.request.copy(
                    uri = URI.create(plan.request.uri.toString().replace("f006", "f009")),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            plan.copy(
                request = plan.request.copy(
                    uri = URI.create(plan.request.uri.toString().replace("leftlon=30.25", "leftlon=30.5")),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            plan.copy(request = plan.request.copy(maxResponseBytes = 4L * 1024L * 1024L))
        }
        assertFailsWith<IllegalArgumentException> {
            plan.copy(request = plan.request.copy(minimumRequestSpacing = Duration.ZERO))
        }
        assertFailsWith<IllegalArgumentException> {
            plan.copy(
                forecastHour = 9,
                validTime = run.plusSeconds(9 * 3600L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            plan.copy(gridPoint = plan.gridPoint.copy(longitudeDegreesEast = 30.5))
        }
    }

    @Test
    fun normalizesWesternLongitudeToGfsDegreesEastGrid() {
        val plan = NoaaGfsRequestPlanner.plan(
            modelRun = Instant.parse("2026-09-10T00:00:00Z"),
            coordinate = coordinate(latitude = 40.7, longitude = -74.0),
            forecastHour = 0,
        )

        assertEquals(40.75, plan.gridPoint.latitude)
        assertEquals(286.0, plan.gridPoint.longitudeDegreesEast)
        assertTrue(plan.request.uri.rawQuery.contains("leftlon=286&rightlon=286"))
    }

    @Test
    fun wrapsNearestGridPointAcrossPrimeMeridian() {
        val plan = NoaaGfsRequestPlanner.plan(
            modelRun = Instant.parse("2026-09-10T18:00:00Z"),
            coordinate = coordinate(latitude = 51.5, longitude = -0.1),
            forecastHour = 1,
        )

        assertEquals(0.0, plan.gridPoint.longitudeDegreesEast)
        assertTrue(plan.request.uri.rawQuery.contains("file=gfs.t18z.pgrb2.0p25.f001"))
        assertTrue(plan.request.uri.rawQuery.contains("leftlon=0&rightlon=0"))
    }

    @Test
    fun rejectsNonOperationalRunCyclesAndOutOfScopeHours() {
        assertFailsWith<IllegalArgumentException> {
            NoaaGfsRequestPlanner.plan(
                modelRun = Instant.parse("2026-09-10T05:00:00Z"),
                coordinate = coordinate(0.0, 0.0),
                forecastHour = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NoaaGfsRequestPlanner.plan(
                modelRun = Instant.parse("2026-09-10T06:30:00Z"),
                coordinate = coordinate(0.0, 0.0),
                forecastHour = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NoaaGfsRequestPlanner.plan(
                modelRun = Instant.parse("2026-09-10T06:00:00Z"),
                coordinate = coordinate(0.0, 0.0),
                forecastHour = 73,
            )
        }
    }

    private fun coordinate(latitude: Double, longitude: Double) = ForecastCoordinate(
        latitude = latitude,
        longitude = longitude,
    )
}
