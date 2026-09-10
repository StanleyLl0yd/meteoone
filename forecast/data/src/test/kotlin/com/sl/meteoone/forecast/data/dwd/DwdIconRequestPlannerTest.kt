package com.sl.meteoone.forecast.data.dwd

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DwdIconRequestPlannerTest {
    @Test
    fun plansOfficialGlobalIcosahedralFieldPath() {
        val modelRun = Instant.parse("2026-09-10T00:00:00Z")
        val plan = DwdIconRequestPlanner.plan(
            modelRun = modelRun,
            forecastHour = 72,
            field = DwdIconField.TEMPERATURE_2M,
        )

        assertEquals(
            "https://opendata.dwd.de/weather/nwp/icon/grib/00/t_2m/" +
                "icon_global_icosahedral_single-level_2026091000_072_T_2M.grib2.bz2",
            plan.request.uri.toString(),
        )
        assertEquals(ForecastProvider.DWD_OPEN_DATA, plan.provider)
        assertEquals(ModelFamily.DWD_ICON, plan.modelFamily)
        assertEquals(modelRun.plusSeconds(72 * 3600L), plan.validTime)
        assertTrue(plan.request.maxResponseBytes <= 8L * 1024L * 1024L)
    }

    @Test
    fun plansEveryRequiredFieldWithOfficialDirectoryAndToken() {
        val modelRun = Instant.parse("2026-09-10T12:00:00Z")

        val expected = mapOf(
            DwdIconField.TEMPERATURE_2M to ("t_2m" to "T_2M"),
            DwdIconField.DEW_POINT_2M to ("td_2m" to "TD_2M"),
            DwdIconField.RELATIVE_HUMIDITY_2M to ("relhum_2m" to "RELHUM_2M"),
            DwdIconField.PRESSURE_MEAN_SEA_LEVEL to ("pmsl" to "PMSL"),
            DwdIconField.WIND_U_10M to ("u_10m" to "U_10M"),
            DwdIconField.WIND_V_10M to ("v_10m" to "V_10M"),
            DwdIconField.WIND_MAX_10M to ("vmax_10m" to "VMAX_10M"),
            DwdIconField.TOTAL_PRECIPITATION to ("tot_prec" to "TOT_PREC"),
            DwdIconField.TOTAL_CLOUD_COVER to ("clct" to "CLCT"),
            DwdIconField.WEATHER_CODE to ("ww" to "WW"),
        )

        for ((field, pathParts) in expected) {
            val (directory, token) = pathParts
            val forecastHour = maxOf(1, field.firstForecastHour)
            val uri = DwdIconRequestPlanner.plan(modelRun, forecastHour, field).request.uri.toString()
            assertTrue(uri.contains("/$directory/"), field.name)
            assertTrue(uri.endsWith("_${token}.grib2.bz2"), field.name)
        }
    }

    @Test
    fun supportsHourlyStepsThroughM1HorizonForInstantaneousFields() {
        val modelRun = Instant.parse("2026-09-10T06:00:00Z")

        for (forecastHour in 0..72) {
            assertEquals(
                forecastHour,
                DwdIconRequestPlanner.plan(
                    modelRun,
                    forecastHour,
                    DwdIconField.TEMPERATURE_2M,
                ).forecastHour,
            )
        }
    }

    @Test
    fun rejectsInvalidCyclesAndForecastHours() {
        assertFailsWith<IllegalArgumentException> {
            DwdIconRequestPlanner.plan(
                Instant.parse("2026-09-10T03:00:00Z"),
                1,
                DwdIconField.TEMPERATURE_2M,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DwdIconRequestPlanner.plan(
                Instant.parse("2026-09-10T00:00:00Z"),
                73,
                DwdIconField.TEMPERATURE_2M,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DwdIconRequestPlanner.plan(
                Instant.parse("2026-09-10T00:30:00Z"),
                1,
                DwdIconField.TEMPERATURE_2M,
            )
        }
    }

    @Test
    fun gustMaximumStartsAtHourOne() {
        val modelRun = Instant.parse("2026-09-10T00:00:00Z")

        assertFailsWith<IllegalArgumentException> {
            DwdIconRequestPlanner.plan(modelRun, 0, DwdIconField.WIND_MAX_10M)
        }
        assertEquals(
            1,
            DwdIconRequestPlanner.plan(modelRun, 1, DwdIconField.WIND_MAX_10M).forecastHour,
        )
    }
}
