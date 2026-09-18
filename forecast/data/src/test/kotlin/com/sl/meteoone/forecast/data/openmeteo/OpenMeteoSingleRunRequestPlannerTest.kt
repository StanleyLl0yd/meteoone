package com.sl.meteoone.forecast.data.openmeteo

import com.sl.meteoone.core.model.ForecastCoordinate
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenMeteoSingleRunRequestPlannerTest {
    private val coordinate = ForecastCoordinate(59.9, 30.3)
    private val run = Instant.parse("2026-09-15T00:00:00Z")

    @Test
    fun plansExactBoundedHttpsRunRequest() {
        val request = OpenMeteoSingleRunRequestPlanner.plan(
            model = OpenMeteoModel.ECMWF_IFS,
            coordinate = coordinate,
            modelRun = run,
        )

        assertEquals("https", request.uri.scheme)
        assertEquals("single-runs-api.open-meteo.com", request.uri.host)
        assertEquals("/v1/forecast", request.uri.path)
        assertEquals(OPEN_METEO_SINGLE_RUN_MAX_RESPONSE_BYTES, request.maxResponseBytes)
        assertEquals(run, request.modelRun)

        val query = query(request.uri)
        assertEquals("59.9", query["latitude"])
        assertEquals("30.3", query["longitude"])
        assertEquals("land", query["cell_selection"])
        assertEquals("ecmwf_ifs", query["models"])
        assertEquals(
            OPEN_METEO_SINGLE_RUN_HOURLY_FIELDS.joinToString(","),
            query["hourly"],
        )
        assertEquals("72", query["forecast_hours"])
        assertEquals("UTC", query["timezone"])
        assertEquals("unixtime", query["timeformat"])
        assertEquals("celsius", query["temperature_unit"])
        assertEquals("ms", query["wind_speed_unit"])
        assertEquals("mm", query["precipitation_unit"])
        assertEquals("2026-09-15T00:00", query["run"])
        assertEquals(12, query.size)
    }

    @Test
    fun everyCurrentModelFamilyKeepsItsExactApiIdentity() {
        val expected = mapOf(
            OpenMeteoModel.ECMWF_IFS to "ecmwf_ifs",
            OpenMeteoModel.DWD_ICON_GLOBAL to "icon_global",
            OpenMeteoModel.NOAA_GFS_GLOBAL to "ncep_gfs_global",
        )

        expected.forEach { (model, apiId) ->
            val request = OpenMeteoSingleRunRequestPlanner.plan(
                model = model,
                coordinate = coordinate,
                modelRun = run,
            )
            assertEquals(apiId, query(request.uri)["models"])
            assertEquals(model.modelFamily, request.modelFamily)
        }
    }

    @Test
    fun rejectsNonHourlyInitializationRatherThanRoundingIt() {
        assertFailsWith<IllegalArgumentException> {
            OpenMeteoSingleRunRequestPlanner.plan(
                model = OpenMeteoModel.ECMWF_IFS,
                coordinate = coordinate,
                modelRun = Instant.parse("2026-09-15T00:00:01Z"),
            )
        }
    }

    @Test
    fun requestCannotBeRetargetedOrHaveItsRunChangedByUriCopy() {
        val canonical = OpenMeteoSingleRunRequestPlanner.plan(
            model = OpenMeteoModel.ECMWF_IFS,
            coordinate = coordinate,
            modelRun = run,
        )

        assertFailsWith<IllegalArgumentException> {
            canonical.copy(
                uri = URI.create(
                    canonical.uri.toASCIIString()
                        .replace("single-runs-api.open-meteo.com", "example.com"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            canonical.copy(
                uri = URI.create(
                    canonical.uri.toASCIIString()
                        .replace("2026-09-15T00%3A00", "2026-09-14T00%3A00"),
                ),
            )
        }
    }

    @Test
    fun singleRunFieldSetIsVerificationOnly() {
        assertEquals(
            listOf(
                "temperature_2m",
                "pressure_msl",
                "wind_speed_10m",
                "wind_direction_10m",
                "precipitation",
            ),
            OPEN_METEO_SINGLE_RUN_HOURLY_FIELDS,
        )
        assertTrue("precipitation_probability" !in OPEN_METEO_SINGLE_RUN_HOURLY_FIELDS)
    }

    private fun query(uri: URI): Map<String, String> =
        requireNotNull(uri.rawQuery)
            .split("&")
            .associate { component ->
                val index = component.indexOf("=")
                decode(component.substring(0, index)) to
                    decode(component.substring(index + 1))
            }

    @Suppress("DEPRECATION")
    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
