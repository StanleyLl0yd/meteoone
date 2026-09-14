package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.forecast.data.dwd.DwdIconField
import com.sl.meteoone.forecast.data.dwd.DwdIconGridGeometryPlanner
import com.sl.meteoone.forecast.data.dwd.DwdIconRequestPlanner
import com.sl.meteoone.forecast.data.ecmwf.EcmwfIfsFieldSelector
import com.sl.meteoone.forecast.data.ecmwf.EcmwfIfsRequestPlanner
import com.sl.meteoone.forecast.data.ecmwf.EcmwfSurfaceField
import com.sl.meteoone.forecast.data.noaa.NoaaGfsRequestPlanner
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GribSemanticBindingTest {
    private val modelRun = Instant.parse("2026-09-10T18:00:00Z")
    private val validTime = Instant.parse("2026-09-11T00:00:00Z")
    private val coordinate = ForecastCoordinate(latitude = 48.8, longitude = 2.3)

    @Test
    fun noaaBindsMeasuredInstantaneousTemperature() {
        val field = GribSemanticBinder.bind(
            noaaContext(),
            instant(category = 0, number = 0, surfaceType = 103, surfaceValue = 2, drt = 0),
            selected(281.25),
        )

        requireNotNull(field)
        assertEquals(GribForecastParameter.TEMPERATURE_2M, field.parameter)
        assertEquals(GribValueUnit.KELVIN, field.unit)
        assertEquals(281.25, field.value)
        assertEquals(validTime, field.validTime)
        assertNull(field.intervalStart)
    }

    @Test
    fun noaaIgnoresBroadResponseMessagesOutsideCanonicalSurfaceOrStatistic() {
        assertNull(
            GribSemanticBinder.bind(
                noaaContext(),
                instant(category = 0, number = 0, surfaceType = 103, surfaceValue = 10, drt = 0),
                selected(280.0),
            ),
        )
        assertNull(
            GribSemanticBinder.bind(
                noaaContext(),
                interval(
                    category = 6,
                    number = 1,
                    surfaceType = 10,
                    drt = 0,
                    forecastTimeUnit = 1,
                    forecastTime = 0,
                    statisticalProcess = 0,
                    rangeUnit = 1,
                    rangeLength = 6,
                ),
                selected(80.0),
            ),
        )
    }

    @Test
    fun noaaPrecipitationUsesEncodedRunToValidTimeInterval() {
        val field = GribSemanticBinder.bind(
            noaaContext(),
            interval(
                category = 1,
                number = 8,
                surfaceType = 1,
                drt = 0,
                forecastTimeUnit = 1,
                forecastTime = 0,
                statisticalProcess = 1,
                rangeUnit = 1,
                rangeLength = 6,
            ),
            selected(4.5),
        )

        requireNotNull(field)
        assertEquals(GribForecastParameter.PRECIPITATION_ACCUMULATION, field.parameter)
        assertEquals(GribValueUnit.KILOGRAMS_PER_SQUARE_METRE, field.unit)
        assertEquals(modelRun, field.intervalStart)
        assertEquals(validTime, field.validTime)
    }

    @Test
    fun matchedNoaaFieldFailsClosedOnRunRepresentationAndTimeUnitDrift() {
        val base = instant(category = 0, number = 0, surfaceType = 103, surfaceValue = 2, drt = 0)

        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(noaaContext(), base.copy(referenceTime = modelRun.minusSeconds(21600)), selected(280.0))
        }
        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(noaaContext(), base.copy(dataRepresentationTemplate = 42), selected(280.0))
        }
        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(noaaContext(), base.copy(forecastTimeUnit = 255), selected(280.0))
        }
    }

    @Test
    fun ecmwfPlanRejectsAnotherKnownFieldSignature() {
        val context = ecmwfContext(EcmwfSurfaceField.TEMPERATURE_2M)
        val dewPoint = instant(category = 0, number = 6, surfaceType = 103, surfaceValue = 2, drt = 42)

        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(context, dewPoint, selected(279.0))
        }
    }

    @Test
    fun ecmwfPrecipitationNormalizesMetresAndPreservesInterval() {
        val field = GribSemanticBinder.bind(
            ecmwfContext(EcmwfSurfaceField.TOTAL_PRECIPITATION),
            interval(
                category = 1,
                number = 193,
                surfaceType = 1,
                drt = 42,
                forecastTimeUnit = 1,
                forecastTime = 0,
                statisticalProcess = 1,
                rangeUnit = 1,
                rangeLength = 6,
            ),
            selected(0.0123),
        )

        requireNotNull(field)
        assertEquals(GribForecastParameter.PRECIPITATION_ACCUMULATION, field.parameter)
        assertEquals(12.3, field.value, absoluteTolerance = 1e-12)
        assertEquals(modelRun, field.intervalStart)
    }

    @Test
    fun ecmwfLocalCloudFractionNormalizesToPercent() {
        val field = GribSemanticBinder.bind(
            ecmwfContext(EcmwfSurfaceField.TOTAL_CLOUD_COVER),
            instant(
                category = 6,
                number = 192,
                surfaceType = 1,
                surfaceValue = 0xffff_ffffL,
                surfaceScaleFactor = 255,
                drt = 42,
            ),
            selected(0.42),
        )

        requireNotNull(field)
        assertEquals(GribForecastParameter.TOTAL_CLOUD_COVER, field.parameter)
        assertEquals(GribValueUnit.PERCENT, field.unit)
        assertEquals(42.0, field.value, absoluteTolerance = 1e-12)
    }

    @Test
    fun ecmwfWindGustTimespanComesFromPdt8() {
        val field = GribSemanticBinder.bind(
            ecmwfContext(EcmwfSurfaceField.WIND_GUST_10M_MAXIMUM),
            interval(
                category = 2,
                number = 22,
                surfaceType = 103,
                surfaceValue = 10,
                drt = 42,
                forecastTimeUnit = 1,
                forecastTime = 5,
                statisticalProcess = 2,
                rangeUnit = 1,
                rangeLength = 1,
            ),
            selected(17.5),
        )

        requireNotNull(field)
        assertEquals(GribForecastParameter.WIND_GUST_10M, field.parameter)
        assertEquals(modelRun.plusSeconds(5 * 3600L), field.intervalStart)
        assertEquals(validTime, field.validTime)
    }

    @Test
    fun dwdMinuteBasedIntervalsBindExactly() {
        val gust = GribSemanticBinder.bind(
            dwdContext(DwdIconField.WIND_MAX_10M),
            interval(
                category = 2,
                number = 22,
                surfaceType = 103,
                surfaceValue = 10,
                drt = 42,
                gdt = 101,
                forecastTimeUnit = 0,
                forecastTime = 300,
                statisticalProcess = 2,
                rangeUnit = 0,
                rangeLength = 60,
            ),
            selected(14.0),
        )
        val precipitation = GribSemanticBinder.bind(
            dwdContext(DwdIconField.TOTAL_PRECIPITATION),
            interval(
                category = 1,
                number = 52,
                surfaceType = 1,
                drt = 42,
                gdt = 101,
                forecastTimeUnit = 0,
                forecastTime = 0,
                statisticalProcess = 1,
                rangeUnit = 0,
                rangeLength = 360,
            ),
            selected(6.0),
        )

        requireNotNull(gust)
        requireNotNull(precipitation)
        assertEquals(modelRun.plusSeconds(5 * 3600L), gust.intervalStart)
        assertEquals(modelRun, precipitation.intervalStart)
    }

    @Test
    fun dwdWeatherCodeCannotCrossPointFieldBoundary() {
        assertFailsWith<IllegalStateException> {
            GribSemanticBinder.bind(
                dwdContext(DwdIconField.WEATHER_CODE),
                instant(category = 19, number = 25, surfaceType = 1, drt = 42, gdt = 101),
                selected(1.0),
            )
        }
    }

    @Test
    fun intervalMetadataFailsClosedOnProcessLengthEndAndRangeCountDrift() {
        val context = ecmwfContext(EcmwfSurfaceField.WIND_GUST_10M_MAXIMUM)
        val base = interval(
            category = 2,
            number = 22,
            surfaceType = 103,
            surfaceValue = 10,
            drt = 42,
            forecastTimeUnit = 1,
            forecastTime = 5,
            statisticalProcess = 2,
            rangeUnit = 1,
            rangeLength = 1,
        )

        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(context, base.copy(statisticalProcess = 1), selected(10.0))
        }
        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(context, base.copy(timeRangeLength = 2), selected(10.0))
        }
        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(context, base.copy(intervalEnd = validTime.plusSeconds(3600)), selected(10.0))
        }
        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(context, base.copy(numberOfTimeRanges = 2), selected(10.0))
        }
    }

    private fun noaaContext(): OfficialGribDecodeContext.Noaa {
        val plan = NoaaGfsRequestPlanner.plan(modelRun, coordinate, 6)
        return GribDecodeRequest.noaa(byteArrayOf(1), plan).context as OfficialGribDecodeContext.Noaa
    }

    private fun ecmwfContext(field: EcmwfSurfaceField): OfficialGribDecodeContext.Ecmwf {
        val requestPlan = EcmwfIfsRequestPlanner.plan(modelRun, 6)
        val fieldPlan = EcmwfIfsFieldSelector.select(
            indexContent = """{"domain":"g","date":"20260910","time":"1800","class":"od","type":"fc","stream":"oper","step":"6","levtype":"sfc","param":"${field.parameter}","_offset":0,"_length":10}""",
            plan = requestPlan,
            fields = setOf(field),
        ).single()
        return GribDecodeRequest.ecmwf(byteArrayOf(1), fieldPlan, coordinate).context as OfficialGribDecodeContext.Ecmwf
    }

    private fun dwdContext(field: DwdIconField): OfficialGribDecodeContext.Dwd {
        val plan = DwdIconRequestPlanner.plan(modelRun, 6, field)
        return GribDecodeRequest.dwd(
            byteArrayOf(1),
            plan,
            coordinate,
            DwdIconGridGeometryPlanner.plan(modelRun),
        ).context as OfficialGribDecodeContext.Dwd
    }

    private fun selected(value: Double) = SelectedGribGridPoint(
        index = 0,
        latitude = coordinate.latitude,
        longitudeDegreesEast = coordinate.longitude,
        value = value,
    )

    private fun instant(
        category: Int,
        number: Int,
        surfaceType: Int,
        surfaceValue: Long = 0,
        surfaceScaleFactor: Int = 0,
        drt: Int,
        gdt: Int = 0,
    ) = GribMessageMetadata(
        edition = 2,
        discipline = 0,
        parameterCategory = category,
        parameterNumber = number,
        productDefinitionTemplate = 0,
        gridDefinitionTemplate = gdt,
        dataRepresentationTemplate = drt,
        referenceTime = modelRun,
        forecastTimeUnit = if (gdt == 101) 0 else 1,
        forecastTime = if (gdt == 101) 360 else 6,
        firstFixedSurfaceType = surfaceType,
        firstFixedSurfaceScaleFactor = surfaceScaleFactor,
        firstFixedSurfaceScaledValue = surfaceValue,
    )

    private fun interval(
        category: Int,
        number: Int,
        surfaceType: Int,
        surfaceValue: Long = 0,
        surfaceScaleFactor: Int = 0,
        drt: Int,
        gdt: Int = 0,
        forecastTimeUnit: Int,
        forecastTime: Long,
        statisticalProcess: Int,
        rangeUnit: Int,
        rangeLength: Long,
    ) = GribMessageMetadata(
        edition = 2,
        discipline = 0,
        parameterCategory = category,
        parameterNumber = number,
        productDefinitionTemplate = 8,
        gridDefinitionTemplate = gdt,
        dataRepresentationTemplate = drt,
        referenceTime = modelRun,
        forecastTimeUnit = forecastTimeUnit,
        forecastTime = forecastTime,
        firstFixedSurfaceType = surfaceType,
        firstFixedSurfaceScaleFactor = surfaceScaleFactor,
        firstFixedSurfaceScaledValue = surfaceValue,
        intervalEnd = validTime,
        numberOfTimeRanges = 1,
        statisticalProcess = statisticalProcess,
        timeRangeUnit = rangeUnit,
        timeRangeLength = rangeLength,
    )
}
