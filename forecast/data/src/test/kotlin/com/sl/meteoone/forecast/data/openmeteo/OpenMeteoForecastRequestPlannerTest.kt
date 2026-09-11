package com.sl.meteoone.forecast.data.openmeteo

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenMeteoForecastRequestPlannerTest {
    private val coordinate = ForecastCoordinate(latitude = 59.9, longitude = 30.3)

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
            )
            assertEquals(ForecastProvider.OPEN_METEO, request.provider)
            assertEquals(identity.second, request.modelFamily)
            assertEquals(model, request.model)
            assertEquals(coordinate, request.coordinate)
        }
    }

    @Test
    fun plansBoundedSeventyTwoHourUtcRequestFromNormalizedCoordinate() {
        val request = OpenMeteoForecastRequestPlanner.plan(
            model = OpenMeteoModel.NOAA_GFS_GLOBAL,
            coordinate = coordinate,
        )

        val uri = request.uri
        assertEquals("https", uri.scheme)
        assertEquals("api.open-meteo.com", uri.host)
        assertEquals("/v1/forecast", uri.path)
        assertEquals(512L * 1024L, request.maxResponseBytes)

        val query = uri.rawQuery
        assertTrue(query.contains("latitude=59.9"))
        assertTrue(query.contains("longitude=30.3"))
        assertTrue(query.contains("models=ncep_gfs_global"))
        assertTrue(query.contains("forecast_hours=72"))
        assertTrue(query.contains("timezone=UTC"))
        assertTrue(query.contains("timeformat=unixtime"))
        assertTrue(query.contains("temperature_unit=celsius"))
        assertTrue(query.contains("wind_speed_unit=ms"))
        assertTrue(query.contains("precipitation_unit=mm"))
        OPEN_METEO_HOURLY_FIELDS.forEach { field -> assertTrue(query.contains(field)) }
        assertFalse(query.contains("precipitation_probability"))
    }

    @Test
    fun requestContractRejectsModelCoordinateAndSemanticQueryDrift() {
        val request = OpenMeteoForecastRequestPlanner.plan(
            model = OpenMeteoModel.NOAA_GFS_GLOBAL,
            coordinate = coordinate,
        )

        assertFailsWith<IllegalArgumentException> {
            request.copy(model = OpenMeteoModel.ECMWF_IFS)
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(coordinate = ForecastCoordinate(latitude = 60.0, longitude = 30.3))
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
            request.copy(uri = URI.create(request.uri.toString().replace("&timezone=UTC", "")))
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(uri = URI.create(request.uri.toString() + "&past_days=1"))
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(
                uri = URI.create(request.uri.toString().replace("forecast_hours=72", "forecast_hours=73")),
            )
        }
    }

    @Test
    fun requestContractAllowsEquivalentQueryOrdering() {
        val request = OpenMeteoForecastRequestPlanner.plan(
            model = OpenMeteoModel.DWD_ICON_GLOBAL,
            coordinate = coordinate,
        )
        val reorderedQuery = request.uri.rawQuery.split('&').reversed().joinToString("&")

        val reordered = request.copy(
            uri = URI(
                request.uri.scheme,
                request.uri.authority,
                request.uri.path,
                reorderedQuery,
                null,
            ),
        )

        assertEquals(request.model, reordered.model)
        assertEquals(request.coordinate, reordered.coordinate)
    }

    @Test
    fun requestContractRejectsNonOpenMeteoEndpointsAndOversizedLimits() {
        assertFailsWith<IllegalArgumentException> {
            OpenMeteoForecastRequest(
                uri = URI.create("http://api.open-meteo.com/v1/forecast"),
                model = OpenMeteoModel.ECMWF_IFS,
                coordinate = coordinate,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OpenMeteoForecastRequest(
                uri = URI.create("https://example.com/v1/forecast"),
                model = OpenMeteoModel.ECMWF_IFS,
                coordinate = coordinate,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OpenMeteoForecastRequest(
                uri = URI.create("https://api.open-meteo.com/v1/forecast"),
                model = OpenMeteoModel.ECMWF_IFS,
                coordinate = coordinate,
                maxResponseBytes = 512L * 1024L + 1L,
            )
        }
    }
}
