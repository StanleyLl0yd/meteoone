package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.forecast.data.dwd.DwdIconGridGeometryPlanner
import com.sl.meteoone.forecast.data.dwd.DwdIconRequestPlanner
import com.sl.meteoone.forecast.data.dwd.DwdIconField
import com.sl.meteoone.forecast.data.ecmwf.EcmwfFieldRangePlan
import com.sl.meteoone.forecast.data.ecmwf.EcmwfIfsRequestPlanner
import com.sl.meteoone.forecast.data.ecmwf.EcmwfSurfaceField
import com.sl.meteoone.forecast.data.noaa.NoaaGfsRequestPlanner
import com.sl.meteoone.forecast.data.source.ByteRange
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GribPointSelectionTest {
    private val modelRun = Instant.parse("2026-09-10T00:00:00Z")

    @Test
    fun noaaRequiresExactPlannedProviderPointAndSingleValue() {
        val plan = NoaaGfsRequestPlanner.plan(
            modelRun = modelRun,
            coordinate = ForecastCoordinate(59.9, 30.4),
            forecastHour = 3,
        )
        val context = GribDecodeRequest.noaa(byteArrayOf(1), plan).context as OfficialGribDecodeContext.Noaa

        val selected = GribPointSelector.selectNoaa(
            context = context,
            gridDefinitionTemplate = 0,
            latitudes = doubleArrayOf(plan.gridPoint.latitude),
            longitudesDegreesEast = doubleArrayOf(plan.gridPoint.longitudeDegreesEast),
            values = doubleArrayOf(280.0),
        )

        assertEquals(0, selected.index)
        assertEquals(plan.gridPoint.latitude, selected.latitude)
        assertEquals(plan.gridPoint.longitudeDegreesEast, selected.longitudeDegreesEast)
        assertEquals(280.0, selected.value)

        assertFailsWith<IllegalArgumentException> {
            GribPointSelector.selectNoaa(
                context = context,
                gridDefinitionTemplate = 0,
                latitudes = doubleArrayOf(plan.gridPoint.latitude + 0.25),
                longitudesDegreesEast = doubleArrayOf(plan.gridPoint.longitudeDegreesEast),
                values = doubleArrayOf(280.0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GribPointSelector.selectNoaa(
                context = context,
                gridDefinitionTemplate = 0,
                latitudes = doubleArrayOf(plan.gridPoint.latitude, plan.gridPoint.latitude),
                longitudesDegreesEast = doubleArrayOf(
                    plan.gridPoint.longitudeDegreesEast,
                    plan.gridPoint.longitudeDegreesEast,
                ),
                values = doubleArrayOf(280.0, 281.0),
            )
        }
    }

    @Test
    fun ecmwfRegularGridHandlesLongitudeWrapAndDeterministicHalfCellTies() {
        val geometry = globalQuarterGrid()
        val values = DoubleArray(geometry.pointCount) { it.toDouble() }

        val wrapped = GribPointSelector.selectEcmwf(
            context = ecmwfContext(ForecastCoordinate(0.0, -90.0)),
            gridDefinitionTemplate = 0,
            geometry = geometry,
            values = values,
        )
        assertEquals(7, wrapped.index)
        assertEquals(0.0, wrapped.latitude)
        assertEquals(270.0, wrapped.longitudeDegreesEast)

        val tied = GribPointSelector.selectEcmwf(
            context = ecmwfContext(ForecastCoordinate(45.0, 45.0)),
            gridDefinitionTemplate = 0,
            geometry = geometry,
            values = values,
        )
        assertEquals(0, tied.index)
        assertEquals(90.0, tied.latitude)
        assertEquals(0.0, tied.longitudeDegreesEast)
    }

    @Test
    fun ecmwfFailsClosedOnShapeCardinalityAndSelectedNonFiniteValue() {
        val geometry = globalQuarterGrid()
        val context = ecmwfContext(ForecastCoordinate(0.0, -90.0))

        assertFailsWith<IllegalArgumentException> {
            GribPointSelector.selectEcmwf(
                context = context,
                gridDefinitionTemplate = 101,
                geometry = geometry,
                values = DoubleArray(geometry.pointCount),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GribPointSelector.selectEcmwf(
                context = context,
                gridDefinitionTemplate = 0,
                geometry = geometry,
                values = DoubleArray(geometry.pointCount - 1),
            )
        }

        val nonFinite = DoubleArray(geometry.pointCount)
        nonFinite[7] = Double.NaN
        assertFailsWith<IllegalArgumentException> {
            GribPointSelector.selectEcmwf(
                context = context,
                gridDefinitionTemplate = 0,
                geometry = geometry,
                values = nonFinite,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            EcmwfRegularLatLonGeometry(
                latitudeCount = 3,
                longitudeCount = 4,
                firstLatitude = 90.0,
                firstLongitudeDegreesEast = 0.0,
                latitudeIncrementDegrees = 90.0,
                longitudeIncrementDegrees = 90.0,
                iScansNegatively = false,
                jScansPositively = false,
                jPointsAreConsecutive = true,
                alternativeRowScanning = false,
            )
        }
    }

    @Test
    fun dwdSelectsAcrossDatelineAndUsesLowestIndexForExactTie() {
        val context = dwdContext(ForecastCoordinate(0.0, -0.1))
        val geometry = DwdIconGridGeometry(
            modelRun = modelRun,
            latitudes = doubleArrayOf(0.0, 0.0),
            longitudesDegreesEast = doubleArrayOf(359.0, 1.0),
        )

        val selected = GribPointSelector.selectDwd(
            context = context,
            gridDefinitionTemplate = 101,
            geometry = geometry,
            values = doubleArrayOf(10.0, 20.0),
        )
        assertEquals(0, selected.index)
        assertEquals(10.0, selected.value)

        val tieContext = dwdContext(ForecastCoordinate(0.0, 0.0))
        val tied = GribPointSelector.selectDwd(
            context = tieContext,
            gridDefinitionTemplate = 101,
            geometry = geometry,
            values = doubleArrayOf(10.0, 20.0),
        )
        assertEquals(0, tied.index)
    }

    @Test
    fun dwdRequiresMatchingGeometryRunCardinalityAndFiniteSelectedValue() {
        val context = dwdContext(ForecastCoordinate(0.0, 0.0))
        val geometry = DwdIconGridGeometry(
            modelRun = modelRun,
            latitudes = doubleArrayOf(0.0),
            longitudesDegreesEast = doubleArrayOf(0.0),
        )

        assertFailsWith<IllegalArgumentException> {
            GribPointSelector.selectDwd(
                context = context,
                gridDefinitionTemplate = 0,
                geometry = geometry,
                values = doubleArrayOf(1.0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GribPointSelector.selectDwd(
                context = context,
                gridDefinitionTemplate = 101,
                geometry = geometry,
                values = doubleArrayOf(1.0, 2.0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GribPointSelector.selectDwd(
                context = context,
                gridDefinitionTemplate = 101,
                geometry = geometry,
                values = doubleArrayOf(Double.POSITIVE_INFINITY),
            )
        }

        val otherRun = modelRun.plusSeconds(6 * 3600L)
        val otherGeometry = DwdIconGridGeometry(
            modelRun = otherRun,
            latitudes = doubleArrayOf(0.0),
            longitudesDegreesEast = doubleArrayOf(0.0),
        )
        assertFailsWith<IllegalArgumentException> {
            GribPointSelector.selectDwd(
                context = context,
                gridDefinitionTemplate = 101,
                geometry = otherGeometry,
                values = doubleArrayOf(1.0),
            )
        }
    }

    @Test
    fun dwdDecodeRequestRejectsGeometryPlanFromAnotherRun() {
        val fieldPlan = DwdIconRequestPlanner.plan(
            modelRun = modelRun,
            forecastHour = 3,
            field = DwdIconField.TEMPERATURE_2M,
        )
        val otherGeometryPlan = DwdIconGridGeometryPlanner.plan(modelRun.plusSeconds(6 * 3600L))

        assertFailsWith<IllegalArgumentException> {
            GribDecodeRequest.dwd(
                payload = byteArrayOf(1),
                plan = fieldPlan,
                coordinate = ForecastCoordinate(0.0, 0.0),
                geometryPlan = otherGeometryPlan,
            )
        }
    }

    private fun globalQuarterGrid() = EcmwfRegularLatLonGeometry(
        latitudeCount = 3,
        longitudeCount = 4,
        firstLatitude = 90.0,
        firstLongitudeDegreesEast = 0.0,
        latitudeIncrementDegrees = 90.0,
        longitudeIncrementDegrees = 90.0,
        iScansNegatively = false,
        jScansPositively = false,
        jPointsAreConsecutive = false,
        alternativeRowScanning = false,
    )

    private fun ecmwfContext(coordinate: ForecastCoordinate): OfficialGribDecodeContext.Ecmwf {
        val requestPlan = EcmwfIfsRequestPlanner.plan(modelRun = modelRun, forecastHour = 3)
        val range = ByteRange(offset = 0, length = 128)
        val fieldPlan = EcmwfFieldRangePlan(
            request = OfficialSourceRequest(
                uri = requestPlan.gribUri,
                maxResponseBytes = range.length,
            ),
            range = range,
            field = EcmwfSurfaceField.TEMPERATURE_2M,
            provider = requestPlan.provider,
            modelFamily = requestPlan.modelFamily,
            modelRun = requestPlan.modelRun,
            validTime = requestPlan.validTime,
            forecastHour = requestPlan.forecastHour,
        )
        return GribDecodeRequest.ecmwf(
            payload = byteArrayOf(1),
            plan = fieldPlan,
            coordinate = coordinate,
        ).context as OfficialGribDecodeContext.Ecmwf
    }

    private fun dwdContext(coordinate: ForecastCoordinate): OfficialGribDecodeContext.Dwd {
        val fieldPlan = DwdIconRequestPlanner.plan(
            modelRun = modelRun,
            forecastHour = 3,
            field = DwdIconField.TEMPERATURE_2M,
        )
        val geometryPlan = DwdIconGridGeometryPlanner.plan(modelRun)
        return GribDecodeRequest.dwd(
            payload = byteArrayOf(1),
            plan = fieldPlan,
            coordinate = coordinate,
            geometryPlan = geometryPlan,
        ).context as OfficialGribDecodeContext.Dwd
    }
}
