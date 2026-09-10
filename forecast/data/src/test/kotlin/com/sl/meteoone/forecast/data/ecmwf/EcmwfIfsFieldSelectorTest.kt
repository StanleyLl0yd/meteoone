package com.sl.meteoone.forecast.data.ecmwf

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EcmwfIfsFieldSelectorTest {
    private val modelRun = Instant.parse("2026-09-10T06:00:00Z")
    private val plan = EcmwfIfsRequestPlanner.plan(modelRun, 6)

    @Test
    fun selectsOnlyRequestedSurfaceFieldsAndPreservesProvenance() {
        val index = listOf(
            line(param = "2t", offset = 100, length = 20),
            line(param = "2d", offset = 120, length = 21),
            line(param = "msl", offset = 141, length = 22),
            line(param = "q", offset = 163, length = 23, levelType = "pl"),
        ).joinToString("\n")

        val selected = EcmwfIfsFieldSelector.select(
            indexContent = index,
            plan = plan,
            fields = setOf(
                EcmwfSurfaceField.TEMPERATURE_2M,
                EcmwfSurfaceField.PRESSURE_MEAN_SEA_LEVEL,
            ),
        )

        assertEquals(2, selected.size)
        assertEquals(EcmwfSurfaceField.TEMPERATURE_2M, selected[0].field)
        assertEquals("bytes=100-119", selected[0].range.headerValue)
        assertEquals(20, selected[0].request.maxResponseBytes)
        assertEquals(plan.gribUri, selected[0].request.uri)
        assertEquals(ForecastProvider.ECMWF_OPEN_DATA, selected[0].provider)
        assertEquals(ModelFamily.ECMWF_IFS, selected[0].modelFamily)
        assertEquals(modelRun, selected[0].modelRun)
        assertEquals(plan.validTime, selected[0].validTime)

        assertEquals(EcmwfSurfaceField.PRESSURE_MEAN_SEA_LEVEL, selected[1].field)
        assertEquals("bytes=141-162", selected[1].range.headerValue)
    }

    @Test
    fun exposesExplicitRequiredSurfaceParameterMapping() {
        assertEquals(
            mapOf(
                EcmwfSurfaceField.TEMPERATURE_2M to "2t",
                EcmwfSurfaceField.DEW_POINT_2M to "2d",
                EcmwfSurfaceField.PRESSURE_MEAN_SEA_LEVEL to "msl",
                EcmwfSurfaceField.WIND_U_10M to "10u",
                EcmwfSurfaceField.WIND_V_10M to "10v",
                EcmwfSurfaceField.WIND_GUST_10M_LAST_3H to "10fg3",
                EcmwfSurfaceField.TOTAL_PRECIPITATION to "tp",
                EcmwfSurfaceField.TOTAL_CLOUD_COVER to "tcc",
            ),
            EcmwfSurfaceField.entries.associateWith { it.parameter },
        )
    }

    @Test
    fun rejectsWrongRunStepStreamTypeClassOrDomain() {
        val mutations = listOf(
            line(param = "2t", date = "20260909"),
            line(param = "2t", time = "0000"),
            line(param = "2t", step = "9"),
            line(param = "2t", stream = "enfo"),
            line(param = "2t", type = "pf"),
            line(param = "2t", dataClass = "rd"),
            line(param = "2t", domain = "l"),
        )

        for (index in mutations) {
            assertFailsWith<IllegalArgumentException> {
                EcmwfIfsFieldSelector.select(
                    indexContent = index,
                    plan = plan,
                    fields = setOf(EcmwfSurfaceField.TEMPERATURE_2M),
                )
            }
        }
    }

    @Test
    fun rejectsDuplicateMissingOrWrongLevelSelectedFields() {
        assertFailsWith<IllegalArgumentException> {
            EcmwfIfsFieldSelector.select(
                indexContent = line(param = "2t") + "\n" + line(param = "2t", offset = 20),
                plan = plan,
                fields = setOf(EcmwfSurfaceField.TEMPERATURE_2M),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EcmwfIfsFieldSelector.select(
                indexContent = line(param = "msl"),
                plan = plan,
                fields = setOf(EcmwfSurfaceField.TEMPERATURE_2M),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EcmwfIfsFieldSelector.select(
                indexContent = line(param = "2t", levelType = "pl"),
                plan = plan,
                fields = setOf(EcmwfSurfaceField.TEMPERATURE_2M),
            )
        }
    }

    @Test
    fun rejectsOversizedSelectedField() {
        assertFailsWith<IllegalArgumentException> {
            EcmwfIfsFieldSelector.select(
                indexContent = line(param = "2t", length = 16L * 1024L * 1024L + 1),
                plan = plan,
                fields = setOf(EcmwfSurfaceField.TEMPERATURE_2M),
            )
        }
    }

    private fun line(
        param: String,
        offset: Long = 0,
        length: Long = 10,
        domain: String = "g",
        date: String = "20260910",
        time: String = "0600",
        dataClass: String = "od",
        type: String = "fc",
        stream: String = "oper",
        step: String = "6",
        levelType: String = "sfc",
    ): String =
        """{"domain":"$domain","date":"$date","time":"$time","class":"$dataClass","type":"$type","stream":"$stream","step":"$step","levtype":"$levelType","param":"$param","_offset":$offset,"_length":$length}"""
}
