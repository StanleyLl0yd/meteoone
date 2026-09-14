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
    fun noaaInstantaneousTemperatureBindsMeasuredSignature() {
        val field = GribSemanticBinder.bind(
            context = noaaContext(),
            metadata = noaaInstant(
                category = 0,
                number = 0,
                surfaceType = 103,
                surfaceValue = 2,
            ),
            selectedPoint = selected(281.25),
        )

        requireNotNull(field)
        assertEquals(GribForecastParameter.TEMPERATURE_2M, field.parameter)
        assertEquals(GribValueUnit.KELVIN, field.unit)
        assertEquals(281.25, field.value)
        assertEquals(validTime, field.validTime)
        assertNull(field.intervalStart)
    }

    @Test
    fun noaaTimeAveragedCloudCoverIsNotPromotedToInstantaneousField() {
        val metadata = noaaInterval(
            category = 6,
            number = 1,
            surfaceType = 10,
            forecastTime = 0,
            statisticalProcess = 0,
            rangeLength = 6,
        )

        assertNull(
            GribSemanticBinder.bind(
                context = noaaContext(),
                metadata = metadata,
                selectedPoint = selected(80.0),
            ),
        )
    }

    @Test
    fun noaaPrecipitationUsesEncodedRunToValidTimeInterval() {
        val field = GribSemanticBinder.bind(
            context = noaaContext(),
            metadata = noaaInterval(
                category = 1,
                number = 8,
                surfaceType = 1,
                forecastTime = 0,
                statisticalProcess = 1,
                rangeLength = 6,
            ),
            selectedPoint = selected(4.5),
        )

        requireNotNull(field)
        assertEquals(GribForecastParameter.PRECIPITATION_ACCUMULATION, field.parameter)
        assertEquals(GribValueUnit.KILOGRAMS_PER_SQUARE_METRE, field.unit)
        assertEquals(modelRun, field.intervalStart)
        assertEquals(validTime, field.validTime)
    }

    @Test
    fun noaaRejectsContradictorySurfaceRepresentationAndRun() {
        val context = noaaContext()

        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(
                context,
                noaaInstant(category = 0, number = 0, surfaceType = 103, surfaceValue = 10),
                selected(280.0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(
                context,
                noaaInstant(
                    category = 0,
                    number = 0,
                    surfaceType = 103,
                    surfaceValue = 2,
                    referenceTime = modelRun.minusSeconds(6 * 3600L),
                ),
                selected(280.0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(
                context,
                noaaInstant(
                    category = 0,
                    number = 0,
                    surfaceType = 103,
                    surfaceValue = 2,
                    dataRepresentationTemplate = 42,
                ),
                selected(280.0),
            )
        }
    }

    @Test
    fun ecmwfPlanRejectsAnotherKnownFieldSignature() {
        val context = ecmwfContext(EcmwfSurfaceField.TEMPERATURE_2M)
        val dewPointMetadata = ecmwfInstant(
            category = 0,
            number = 6,
            surfaceType = 103,
            surfaceScaleFactor = 0,
            surfaceValue = 2,
        )

        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(context, dewPointMetadata, selected(279.0))
        }
    }

    @Test
    fun ecmwfPrecipitationNormalizesMetresAndPreservesEncodedInterval() {
        val context = ecmwfContext(EcmwfSurfaceField.TOTAL_PRECIPITATION)
        val field = GribSemanticBinder.bind(
            context = context,
            metadata = ecmwfInterval(
                category = 1,
                number = 193,
                surfaceType = 1,
                forecastTime = 0,
                statisticalProcess = 1,
                rangeLength = 6,
            ),
            selectedPoint = selected(0.0123),
        )

        requireNotNull(field)
        assertEquals(GribForecastParameter.PRECIPITATION_ACCUMULATION, field.parameter)
        assertEquals(GribValueUnit.KILOGRAMS_PER_SQUARE_METRE, field.unit)
        assertEquals(12.3, field.value, absoluteTolerance = 1e-12)
        assertEquals(modelRun, field.intervalStart)
    }

    @Test
    fun ecmwfLocalCloudFractionNormalizesToPercent() {
        val field = GribSemanticBinder.bind(
            context = ecmwfContext(EcmwfSurfaceField.TOTAL_CLOUD_COVER),
            metadata = ecmwfInstant(
                category = 6,
                number = 192,
                surfaceType = 1,
                surfaceScaleFactor = 255,
                surfaceValue = 0xffff_ffffL,
            ),
            selectedPoint = selected(0.42),
        )

        requireNotNull(field)
        assertEquals(GribForecastParameter.TOTAL_CLOUD_COVER, field.parameter)
        assertEquals(GribValueUnit.PERCENT, field.unit)
        assertEquals(42.0, field.value, absoluteTolerance = 1e-12)
    }

    @Test
    fun ecmwfWindGustTimespanComesFromPdt8NotFieldName() {
        val field = GribSemanticBinder.bind(
            context = ecmwfContext(EcmwfSurfaceField.WIND_GUST_10M_MAXIMUM),
            metadata = ecmwfInterval(
                category = 2,
                number = 22,
                surfaceType = 103,
                surfaceScaleFactor = 0,
                surfaceValue = 10,
                forecastTime = 5,
                statisticalProcess = 2,
                rangeLength = 1,
            ),
            selectedPoint = selected(17.5),
        )

        requireNotNull(field)
        assertEquals(GribForecastParameter.WIND_GUST_10M, field.parameter)
        assertEquals(modelRun.plusSeconds(5 * 3600L), field.intervalStart)
        assertEquals(validTime, field.validTime)
    }

    @Test
    fun dwdMinuteBasedPdt8IntervalsBindExactly() {
        val gust = GribSemanticBinder.bind(
            context = dwdContext(DwdIconField.WIND_MAX_10M),
            metadata = dwdInterval(
                category = 2,
                number = 22,
                surfaceType = 103,
                surfaceValue = 10,
                forecastMinutes = 300,
                statisticalProcess = 2,
                rangeMinutes = 60,
            ),
            selectedPoint = selected(14.0),
        )
        val precipitation = GribSemanticBinder.bind(
            context = dwdContext(DwdIconField.TOTAL_PRECIPITATION),
            metadata = dwdInterval(
                category = 1,
                number = 52,
                surfaceType = 1,
                surfaceValue = 0,
                forecastMinutes = 0,
                statisticalProcess = 1,
                rangeMinutes = 360,
            ),
            selectedPoint = selected(6.0),
        )

        requireNotNull(gust)
        requireNotNull(precipitation)
        assertEquals(modelRun.plusSeconds(5 * 3600L), gust.intervalStart)
        assertEquals(modelRun, precipitation.intervalStart)
        assertEquals(validTime, gust.validTime)
        assertEquals(validTime, precipitation.validTime)
    }

    @Test
    fun dwdWeatherCodeCannotLeakThroughPointFieldBoundary() {
        val metadata = dwdInstant(
            category = 19,
            number = 25,
            surfaceType = 1,
            surfaceValue = 0,
        )

        assertFailsWith<IllegalStateException> {
            GribSemanticBinder.bind(
                dwdContext(DwdIconField.WEATHER_CODE),
                metadata,
                selected(1.0),
            )
        }
    }

    @Test
    fun intervalMetadataFailsClosedOnProcessLengthAndEndDrift() {
        val context = ecmwfContext(EcmwfSurfaceField.WIND_GUST_10M_MAXIMUM)
        val base = ecmwfInterval(
            category = 2,
            number = 22,
            surfaceType = 103,
            surfaceScaleFactor = 0,
            surfaceValue = 10,
            forecastTime = 5,
            statisticalProcess = 2,
            rangeLength = 1,
        )

        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(context, base.copy(statisticalProcess = 1), selected(10.0))
        }
        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(context, base.copy(timeRangeLength = 2), selected(10.0))
        }
        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(
                context,
                base.copy(intervalEnd = validTime.plusSeconds(3600)),
                selected(10.0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(context, base.copy(numberOfTimeRanges = 2), selected(10.0))
        }
    }

    @Test
    fun unsupportedForecastTimeUnitFailsClosed() {
        val metadata = noaaInstant(
            category = 0,
            number = 0,
            surfaceType = 103,
            surfaceValue = 2,
        ).copy(forecastTimeUnit = 255)

        assertFailsWith<IllegalArgumentException> {
            GribSemanticBinder.bind(noaaContext(), metadata, selected(280.0))
        }
    }

    private fun noaaContext(): OfficialGribDecodeContext.Noaa {
        val plan = NoaaGfsRequestPlanner.plan(modelRun, coordinate, 6)
        return GribDecodeRequest.noaa(byteArrayOf(1), plan).context as OfficialGribDecodeContext.Noaa
    }

    private fun ecmwfContext(field: EcmwfSurfaceField): OfficialGribDecodeContext.Ecmwf {
        val requestPlan = EcmwfIfsRequestPlanner.plan(modelRun, 6)
        val fieldPlan = EcmwfIfsFieldSelector.select(
            indexContent =
                """{"domain":"g","date":"20260910","time":"1800","class":"od","type":"fc","stream":"oper","step":"6","levtype":"sfc","param":"${field.parameter}","_offset":0,"_length":10}""",
            plan = requestPlan,
            fields = setOf(field),
        ).single()
        return GribDecodeRequest.ecmwf(
            payload = byteArrayOf(1),
            plan = fieldPlan,
            coordinate = coordinate,
        ).context as OfficialGribDecodeContext.Ecmwf
    }

    private fun dwdContext(field: DwdIconField): OfficialGribDecodeContext.Dwd {
        val fieldPlan = DwdIconRequestPlanner.plan(modelRun, 6, field)
        return GribDecodeRequest.dwd(
            payload = byteArrayOf(1),
            plan = fieldPlan,
            coordinate = coordinate,
            geometryPlan = DwdIconGridGeometryPlanner.plan(modelRun),
        ).context as OfficialGribDecodeContext.Dwd
    }

    private fun selected(value: Double) = SelectedGribGridPoint(
        index = 0,
        latitude = coordinate.latitude,
        longitudeDegreesEast = coordinate.longitude,
        value = value,
    )

    private fun noaaInstant(
        category: Int,
        number: Int,
        surfaceType: Int,
        surfaceValue: Long,
        referenceTime: Instant = modelRun,
        dataRepresentationTemplate: Int = 0,
    ) = GribMessageMetadata(
        edition = 2,
        discipline = 0,
        parameterCategory = category,
        parameterNumber = number,
        productDefinitionTemplate = 0,
        gridDefinitionTemplate = 0,
        dataRepresentationTemplate = dataRepresentationTemplate,
        referenceTime = referenceTime,
        forecastTimeUnit = 1,
        forecastTime = 6,
        firstFixedSurfaceType = surfaceType,
        firstFixedSurfaceScaleFactor = 0,
        firstFixedSurfaceScaledValue = surfaceValue,
    )

    private fun noaaInterval(
        category: Int,
        number: Int,
        surfaceType: Int,
        forecastTime: Long,
        statisticalProcess: Int,
        rangeLength: Long,
    ) = GribMessageMetadata(
        edition = 2,
        discipline = 0,
        parameterCategory = category,
        parameterNumber = number,
        productDefinitionTemplate = 8,
        gridDefinitionTemplate = 0,
        dataRepresentationTemplate = 0,
        referenceTime = modelRun,
        forecastTimeUnit = 1,
        forecastTime = forecastTime,
        firstFixedSurfaceType = surfaceType,
        firstFixedSurfaceScaleFactor = 0,
        firstFixedSurfaceScaledValue = 0,
        intervalEnd = validTime,
        numberOfTimeRanges = 1,
        statisticalProcess = statisticalProcess,
        timeRangeUnit = 1,
        timeRangeLength = rangeLength,
    )

    private fun ecmwfInstant(
        category: Int,
        number: Int,
        surfaceType: Int,
        surfaceScaleFactor: Int,
        surfaceValue: Long,
    ) = GribMessageMetadata(
        edition = 2,
        discipline = 0,
        parameterCategory = category,
        parameterNumber = number,
        productDefinitionTemplate = 0,
        gridDefinitionTemplate = 0,
        dataRepresentationTemplate = 42,
        referenceTime = modelRun,
        forecastTimeUnit = 1,
        forecastTime = 6,
        firstFixedSurfaceType = surfaceType,
        firstFixedSurfaceScaleFactor = surfaceScaleFactor,
        firstFixedSurfaceScaledValue = surfaceValue,
    )

    private fun ecmwfInterval(
        category: Int,
        number: Int,
        surfaceType: Int,
        surfaceScaleFactor: Int = 255,
        surfaceValue: Long = 0xffff_ffffL,
        forecastTime: Long,
        statisticalProcess: Int,
        rangeLength: Long,
    ) = GribMessageMetadata(
        edition = 2,
        discipline = 0,
        parameterCategory = category,
        parameterNumber = number,
        productDefinitionTemplate = 8,
        gridDefinitionTemplate = 0,
        dataRepresentationTemplate = 42,
        referenceTime = modelRun,
        forecastTimeUnit = 1,
        forecastTime = forecastTime,
        firstFixedSurfaceType = surfaceType,
        firstFixedSurfaceScaleFactor = surfaceScaleFactor,
        firstFixedSurfaceScaledValue = surfaceValue,
        intervalEnd = validTime,
        numberOfTimeRanges = 1,
        statisticalProcess = statisticalProcess,
        timeRangeUnit = 1,
        timeRangeLength = rangeLength,
    )

    private fun dwdInstant(
        category: Int,
        number: Int,
        surfaceType: Int,
        surfaceValue: Long,
    ) = GribMessageMetadata(
        edition = 2,
        discipline = 0,
        parameterCategory = category,
        parameterNumber = number,
        productDefinitionTemplate = 0,
        gridDefinitionTemplate = 101,
        dataRepresentationTemplate = 42,
        referenceTime = modelRun,
        forecastTimeUnit = 0,
        forecastTime = 360,
        firstFixedSurfaceType = surfaceType,
        firstFixedSurfaceScaleFactor = 0,
        firstFixedSurfaceScaledValue = surfaceValue,
    )

    private fun dwdInterval(
        category: Int,
        number: Int,
        surfaceType: Int,
        surfaceValue: Long,
        forecastMinutes: Long,
        statisticalProcess: Int,
        rangeMinutes: Long,
    ) = GribMessageMetadata(
        edition = 2,
        discipline = 0,
        parameterCategory = category,
        parameterNumber = number,
        productDefinitionTemplate = 8,
        gridDefinitionTemplate = 101,
        dataRepresentationTemplate = 42,
        referenceTime = modelRun,
        forecastTimeUnit = 0,
        forecastTime = forecastMinutes,
        firstFixedSurfaceType = surfaceType,
        firstFixedSurfaceScaleFactor = 0,
        firstFixedSurfaceScaledValue = surfaceValue,
        intervalEnd = validTime,
        numberOfTimeRanges = 1,
        statisticalProcess = statisticalProcess,
        timeRangeUnit = 0,
        timeRangeLength = rangeMinutes,
    )
}
