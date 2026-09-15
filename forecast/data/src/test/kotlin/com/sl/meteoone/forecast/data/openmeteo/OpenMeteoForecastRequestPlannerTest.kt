package com.sl.meteoone.forecast.data.openmeteo

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenMeteoForecastRequestPlannerTest {
    private val coordinate = ForecastCoordinate(latitude = 59.9, longitude = 30.3)
    private val generatedAt = Instant.parse("2026-09-10T12:30:00Z")

    @Test
    fun mapsExplicitApiModelsToExistingModelFamilies() {
        val expected = mapOf(
            OpenMeteoModel.ECMWF_IFS to ("ecmwf_ifs" to ModelFamily.ECMWF_IFS),
            OpenMeteoModel.DWD_ICON_GLOBAL to ("icon_global" to ModelFamily.DWD_ICON),
            OpenMeteoModel.NOAA_GFS_GLOBAL to ("ncep_gfs_global" to ModelFamily.NOAA_GFS),
        )

        expected.forEach { (model, identity) ->
            assertEquals(identity.first, model.apiId)
            assertEquals(identity.second, model.modelFamily)

            val request = OpenMeteoForecastRequestPlanner.plan(
                model = model,
                coordinate = coordinate,
                generatedAt = generatedAt,
            )
            assertEquals(ForecastProvider.OPEN_METEO, request.provider)
            assertEquals(identity.second, request.modelFamily)
            assertEquals(model, request.model)
            assertEquals(coordinate, request.coordinate)
            assertEquals(generatedAt, request.generatedAt)
        }
    }

    @Test
    fun plansBoundedSeventyTwoHourUtcRequestFromInjectedGenerationTime() {
        val request = OpenMeteoForecastRequestPlanner.plan(
            model = OpenMeteoModel.NOAA_GFS_GLOBAL,
            coordinate = coordinate,
            generatedAt = generatedAt,
        )

        val uri = request.uri
        assertEquals("https", uri.scheme)
        assertEquals("api.open-meteo.com", uri.host)
        assertEquals("/v1/forecast", uri.path)
        assertEquals(512L * 1024L, request.maxResponseBytes)
        assertEquals(Instant.parse("2026-09-10T13:00:00Z"), request.startHour)
        assertEquals(Instant.parse("2026-09-13T12:00:00Z"), request.endHour)

        val query = uri.rawQuery
        assertTrue(query.contains("latitude=59.9"))
        assertTrue(query.contains("longitude=30.3"))
        assertTrue(query.contains("cell_selection=land"))
        assertTrue(query.contains("models=ncep_gfs_global"))
        assertTrue(query.contains("start_hour=2026-09-10T13%3A00"))
        assertTrue(query.contains("end_hour=2026-09-13T12%3A00"))
        assertFalse(query.contains("forecast_hours="))
        assertTrue(query.contains("timezone=UTC"))
        assertTrue(query.contains("timeformat=unixtime"))
        assertTrue(query.contains("temperature_unit=celsius"))
        assertTrue(query.contains("wind_speed_unit=ms"))
        assertTrue(query.contains("precipitation_unit=mm"))
        OPEN_METEO_HOURLY_FIELDS.forEach { field -> assertTrue(query.contains(field)) }
        assertFalse(query.contains("precipitation_probability"))
    }

    @Test
    fun exactHourGenerationTimeStartsAtThatHourWhileSubsecondsAdvance() {
        val exact = OpenMeteoForecastRequestPlanner.plan(
            model = OpenMeteoModel.ECMWF_IFS,
            coordinate = coordinate,
            generatedAt = Instant.parse("2026-09-10T13:00:00Z"),
        )
        val subsecond = OpenMeteoForecastRequestPlanner.plan(
            model = OpenMeteoModel.ECMWF_IFS,
            coordinate = coordinate,
            generatedAt = Instant.parse("2026-09-10T13:00:00.000000001Z"),
        )

        assertEquals(Instant.parse("2026-09-10T13:00:00Z"), exact.startHour)
        assertEquals(Instant.parse("2026-09-10T14:00:00Z"), subsecond.startHour)
        assertEquals(Instant.parse("2026-09-13T12:00:00Z"), exact.endHour)
        assertEquals(Instant.parse("2026-09-13T13:00:00Z"), subsecond.endHour)
    }

    @Test
    fun requestContractRejectsModelCoordinateGenerationTimeAndSemanticQueryDrift() {
        val request = OpenMeteoForecastRequestPlanner.plan(
            model = OpenMeteoModel.NOAA_GFS_GLOBAL,
            coordinate = coordinate,
            generatedAt = generatedAt,
        )

        assertFailsWith<IllegalArgumentException> {
            request.copy(model = OpenMeteoModel.ECMWF_IFS)
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(coordinate = ForecastCoordinate(latitude = 60.0, longitude = 30.3))
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(generatedAt = generatedAt.plusSeconds(3600))
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(
                uri = URI.create(
                    request.uri.toString().replace(
                        "models=ncep_gfs_global",
                        "models=ecmwf_ifs",
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(
                uri = URI.create(request.uri.toString().replace("latitude=59.9", "latitude=60")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(uri = URI.create(request.uri.toString() + "&models=ncep_gfs_global"))
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(uri = URI.create(request.uri.toString().replace("&cell_selection=land", "")))
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(
                uri = URI.create(
                    request.uri.toString().replace("cell_selection=land", "cell_selection=nearest"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(uri = URI.create(request.uri.toString().replace("&timezone=UTC", "")))
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(uri = URI.create(request.uri.toString() + "&past_days=1"))
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(
                uri = URI.create(
                    request.uri.toString().replace(
                        "start_hour=2026-09-10T13%3A00",
                        "start_hour=2026-09-10T12%3A00",
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(
                uri = URI.create(
                    request.uri.toString().replace(
                        "end_hour=2026-09-13T12%3A00",
                        "end_hour=2026-09-13T13%3A00",
                    ),
                ),
            )
        }
    }

    @Test
    fun requestContractAllowsEquivalentQueryOrdering() {
        val request = OpenMeteoForecastRequestPlanner.plan(
            model = OpenMeteoModel.DWD_ICON_GLOBAL,
            coordinate = coordinate,
            generatedAt = generatedAt,
        )
        val reorderedQuery = request.uri.rawQuery.split('&').reversed().joinToString("&")

        val reordered = request.copy(
            uri = URI.create(
                "${request.uri.scheme}://${request.uri.authority}${request.uri.path}?$reorderedQuery",
            ),
        )

        assertEquals(request.model, reordered.model)
        assertEquals(request.coordinate, reordered.coordinate)
        assertEquals(request.generatedAt, reordered.generatedAt)
    }

    @Test
    fun requestContractRejectsNonOpenMeteoEndpointsAndOversizedLimits() {
        assertFailsWith<IllegalArgumentException> {
            OpenMeteoForecastRequest(
                uri = URI.create("http://api.open-meteo.com/v1/forecast"),
                model = OpenMeteoModel.ECMWF_IFS,
                coordinate = coordinate,
                generatedAt = generatedAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OpenMeteoForecastRequest(
                uri = URI.create("https://example.com/v1/forecast"),
                model = OpenMeteoModel.ECMWF_IFS,
                coordinate = coordinate,
                generatedAt = generatedAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OpenMeteoForecastRequest(
                uri = URI.create("https://api.open-meteo.com/v1/forecast"),
                model = OpenMeteoModel.ECMWF_IFS,
                coordinate = coordinate,
                generatedAt = generatedAt,
                maxResponseBytes = 512L * 1024L + 1L,
            )
        }
    }
}
