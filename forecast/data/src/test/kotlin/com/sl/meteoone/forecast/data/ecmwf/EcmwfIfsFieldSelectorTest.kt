package com.sl.meteoone.forecast.data.ecmwf

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.data.source.ByteRange
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import java.net.URI
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
    fun selectedFieldPlanRejectsIdentityTimeUriAndRangeDrift() {
        val selected = EcmwfIfsFieldSelector.select(
            indexContent = line(param = "2t", offset = 100, length = 20),
            plan = plan,
            fields = setOf(EcmwfSurfaceField.TEMPERATURE_2M),
        ).single()

        assertFailsWith<IllegalArgumentException> {
            recreate(
                selected = selected,
                provider = ForecastProvider.DWD_OPEN_DATA,
                modelFamily = ModelFamily.DWD_ICON,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            recreate(selected = selected, modelFamily = ModelFamily.NOAA_GFS)
        }
        assertFailsWith<IllegalArgumentException> {
            recreate(selected = selected, validTime = selected.validTime.plusSeconds(3600))
        }
        assertFailsWith<IllegalArgumentException> {
            recreate(
                selected = selected,
                forecastHour = 9,
                validTime = modelRun.plusSeconds(9 * 3600L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            recreate(
                selected = selected,
                request = selected.request.copy(uri = URI.create("https://example.com/field.grib2")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            recreate(
                selected = selected,
                request = selected.request.copy(maxResponseBytes = selected.range.length + 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            recreate(
                selected = selected,
                range = ByteRange(offset = selected.range.offset, length = selected.range.length + 1),
            )
        }

        val oversized = 16L * 1024L * 1024L + 1
        assertFailsWith<IllegalArgumentException> {
            EcmwfFieldRangePlan(
                request = OfficialSourceRequest(
                    uri = plan.gribUri,
                    maxResponseBytes = oversized,
                ),
                range = ByteRange(offset = 0, length = oversized),
                field = EcmwfSurfaceField.TEMPERATURE_2M,
                provider = ForecastProvider.ECMWF_OPEN_DATA,
                modelFamily = ModelFamily.ECMWF_IFS,
                modelRun = plan.modelRun,
                validTime = plan.validTime,
                forecastHour = plan.forecastHour,
            )
        }
    }

    @Test
    fun exposesExplicitRequiredSurfaceParameterMapping() {
        assertEquals(
            mapOf(
                EcmwfSurfaceField.TEMPERATURE_2M to setOf("2t"),
                EcmwfSurfaceField.DEW_POINT_2M to setOf("2d"),
                EcmwfSurfaceField.PRESSURE_MEAN_SEA_LEVEL to setOf("msl"),
                EcmwfSurfaceField.WIND_U_10M to setOf("10u"),
                EcmwfSurfaceField.WIND_V_10M to setOf("10v"),
                EcmwfSurfaceField.WIND_GUST_10M_LAST_3H to setOf("10fg", "10fg3"),
                EcmwfSurfaceField.TOTAL_PRECIPITATION to setOf("tp"),
                EcmwfSurfaceField.TOTAL_CLOUD_COVER to setOf("tcc"),
            ),
            EcmwfSurfaceField.entries.associateWith { it.acceptedParameters },
        )
    }

    @Test
    fun acceptsDocumentedOpenDataWindGustIdentifiers() {
        for (parameter in listOf("10fg", "10fg3")) {
            val selected = EcmwfIfsFieldSelector.select(
                indexContent = line(param = parameter),
                plan = plan,
                fields = setOf(EcmwfSurfaceField.WIND_GUST_10M_LAST_3H),
            )

            assertEquals(EcmwfSurfaceField.WIND_GUST_10M_LAST_3H, selected.single().field)
        }
    }

    @Test
    fun rejectsMigrationWindGustIdentityUntilTimespanIsValidated() {
        assertFailsWith<IllegalArgumentException> {
            EcmwfIfsFieldSelector.select(
                indexContent = line(param = "max_i10fg"),
                plan = plan,
                fields = setOf(EcmwfSurfaceField.WIND_GUST_10M_LAST_3H),
            )
        }
    }

    @Test
    fun rejectsAmbiguousWindGustIdentifiers() {
        val index = listOf(
            line(param = "10fg", offset = 0),
            line(param = "10fg3", offset = 10),
        ).joinToString("\n")

        assertFailsWith<IllegalArgumentException> {
            EcmwfIfsFieldSelector.select(
                indexContent = index,
                plan = plan,
                fields = setOf(EcmwfSurfaceField.WIND_GUST_10M_LAST_3H),
            )
        }
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

    private fun recreate(
        selected: EcmwfFieldRangePlan,
        request: OfficialSourceRequest = selected.request,
        range: ByteRange = selected.range,
        provider: ForecastProvider = selected.provider,
        modelFamily: ModelFamily = selected.modelFamily,
        modelRunValue: Instant = selected.modelRun,
        validTime: Instant = selected.validTime,
        forecastHour: Int = selected.forecastHour,
    ) = EcmwfFieldRangePlan(
        request = request,
        range = range,
        field = selected.field,
        provider = provider,
        modelFamily = modelFamily,
        modelRun = modelRunValue,
        validTime = validTime,
        forecastHour = forecastHour,
    )

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
