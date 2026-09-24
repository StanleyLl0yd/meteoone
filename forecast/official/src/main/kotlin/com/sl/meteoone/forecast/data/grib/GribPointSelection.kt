package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.core.model.ForecastCoordinate
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private const val MAX_GRID_POINT_COUNT = 3_000_000
private const val COORDINATE_EPSILON_DEGREES = 1e-6
private const val INDEX_TIE_EPSILON = 1e-12
private const val SPHERICAL_TIE_EPSILON = 1e-15

internal data class SelectedGribGridPoint(
    val index: Int,
    val latitude: Double,
    val longitudeDegreesEast: Double,
    val value: Double,
)

/** Compact GDT 0 geometry; scan modes outside the current ECMWF envelope fail closed. */
internal data class EcmwfRegularLatLonGeometry(
    val latitudeCount: Int,
    val longitudeCount: Int,
    val firstLatitude: Double,
    val firstLongitudeDegreesEast: Double,
    val latitudeIncrementDegrees: Double,
    val longitudeIncrementDegrees: Double,
    val iScansNegatively: Boolean,
    val jScansPositively: Boolean,
    val jPointsAreConsecutive: Boolean,
    val alternativeRowScanning: Boolean,
) {
    val pointCount: Int

    init {
        require(latitudeCount >= 2 && longitudeCount >= 2) {
            "ECMWF regular grid must have at least two points on each axis"
        }
        val count = latitudeCount.toLong() * longitudeCount.toLong()
        require(count in 1..MAX_GRID_POINT_COUNT.toLong()) {
            "ECMWF regular grid cardinality is out of bounds"
        }
        pointCount = count.toInt()

        require(firstLatitude.isFinite() && firstLatitude in -90.0..90.0) {
            "ECMWF first latitude is invalid"
        }
        require(
            firstLongitudeDegreesEast.isFinite() &&
                firstLongitudeDegreesEast >= 0.0 &&
                firstLongitudeDegreesEast < 360.0,
        ) {
            "ECMWF first longitude must use [0, 360) degrees east"
        }
        require(latitudeIncrementDegrees.isFinite() && latitudeIncrementDegrees > 0.0) {
            "ECMWF latitude increment must be finite and positive"
        }
        require(longitudeIncrementDegrees.isFinite() && longitudeIncrementDegrees > 0.0) {
            "ECMWF longitude increment must be finite and positive"
        }
        require(!jPointsAreConsecutive) {
            "ECMWF j-points-consecutive scanning is outside the supported GDT 0 envelope"
        }
        require(!alternativeRowScanning) {
            "ECMWF alternating-row scanning is outside the supported GDT 0 envelope"
        }

        val latitudeStep = if (jScansPositively) latitudeIncrementDegrees else -latitudeIncrementDegrees
        val lastLatitude = firstLatitude + latitudeStep * (latitudeCount - 1)
        require(lastLatitude in -90.0 - COORDINATE_EPSILON_DEGREES..90.0 + COORDINATE_EPSILON_DEGREES) {
            "ECMWF regular grid latitude extent is invalid"
        }
        require(abs(longitudeCount * longitudeIncrementDegrees - 360.0) <= COORDINATE_EPSILON_DEGREES) {
            "ECMWF regular grid must cover exactly one global longitude cycle"
        }
    }
}

/**
 * Decoded DWD CLAT/CLON geometry shared by Android and server official-source decoders.
 *
 * The arrays are adopted without copying to avoid doubling the multi-million-point geometry.
 * Callers inside this module must transfer ownership and must not mutate them afterwards.
 */
class DwdIconGridGeometry(
    val modelRun: Instant,
    private val latitudes: DoubleArray,
    private val longitudesDegreesEast: DoubleArray,
) {
    val pointCount: Int = latitudes.size

    init {
        require(pointCount in 1..MAX_GRID_POINT_COUNT) {
            "DWD ICON grid cardinality is out of bounds"
        }
        require(longitudesDegreesEast.size == pointCount) {
            "DWD ICON latitude/longitude cardinality mismatch"
        }
        require(latitudes.all { it.isFinite() && it in -90.0..90.0 }) {
            "DWD ICON geometry contains an invalid latitude"
        }
        require(longitudesDegreesEast.all { it.isFinite() && it >= 0.0 && it < 360.0 }) {
            "DWD ICON geometry longitudes must use [0, 360) degrees east"
        }
    }

    fun coordinateAt(index: Int): GridCoordinate = GridCoordinate(
        latitude = latitudes[index],
        longitudeDegreesEast = longitudesDegreesEast[index],
    )

    data class GridCoordinate(
        val latitude: Double,
        val longitudeDegreesEast: Double,
    )
}

internal object GribPointSelector {
    fun selectNoaa(
        context: OfficialGribDecodeContext.Noaa,
        gridDefinitionTemplate: Int,
        latitudes: DoubleArray,
        longitudesDegreesEast: DoubleArray,
        values: DoubleArray,
    ): SelectedGribGridPoint {
        require(gridDefinitionTemplate == 0) { "NOAA GFS point subset must decode as GDT 0" }
        requireCardinality(latitudes, longitudesDegreesEast, values, expected = 1, provider = "NOAA")

        val expected = context.plan.gridPoint
        require(abs(latitudes[0] - expected.latitude) <= COORDINATE_EPSILON_DEGREES) {
            "NOAA decoded latitude contradicts the planned provider grid point"
        }
        require(circularLongitudeDifference(longitudesDegreesEast[0], expected.longitudeDegreesEast) <= COORDINATE_EPSILON_DEGREES) {
            "NOAA decoded longitude contradicts the planned provider grid point"
        }
        require(values[0].isFinite()) { "NOAA selected GRIB value must be finite" }

        return SelectedGribGridPoint(
            index = 0,
            latitude = latitudes[0],
            longitudeDegreesEast = normalizeDegreesEast(longitudesDegreesEast[0]),
            value = values[0],
        )
    }

    fun selectEcmwf(
        context: OfficialGribDecodeContext.Ecmwf,
        gridDefinitionTemplate: Int,
        geometry: EcmwfRegularLatLonGeometry,
        values: DoubleArray,
    ): SelectedGribGridPoint {
        require(gridDefinitionTemplate == 0) { "ECMWF IFS field must decode as GDT 0" }
        require(values.size == geometry.pointCount) {
            "ECMWF decoded value cardinality does not match the validated grid geometry"
        }

        val target = context.coordinate
        val latitudeStep = if (geometry.jScansPositively) {
            geometry.latitudeIncrementDegrees
        } else {
            -geometry.latitudeIncrementDegrees
        }
        val latitudePosition = (target.latitude - geometry.firstLatitude) / latitudeStep
        val row = nearestBoundedIndex(latitudePosition, geometry.latitudeCount, "ECMWF latitude")

        val targetLongitude = target.longitude.toDegreesEast()
        val longitudeDelta = if (geometry.iScansNegatively) {
            normalizeDegreesEast(geometry.firstLongitudeDegreesEast - targetLongitude)
        } else {
            normalizeDegreesEast(targetLongitude - geometry.firstLongitudeDegreesEast)
        }
        val column = nearestPeriodicIndex(
            position = longitudeDelta / geometry.longitudeIncrementDegrees,
            count = geometry.longitudeCount,
        )
        val index = row * geometry.longitudeCount + column
        val value = values[index]
        require(value.isFinite()) { "ECMWF selected GRIB value must be finite" }

        val latitude = geometry.firstLatitude + latitudeStep * row
        val longitudeDirection = if (geometry.iScansNegatively) -1.0 else 1.0
        val longitude = normalizeDegreesEast(
            geometry.firstLongitudeDegreesEast +
                longitudeDirection * geometry.longitudeIncrementDegrees * column,
        )
        return SelectedGribGridPoint(index, latitude, longitude, value)
    }

    fun selectDwd(
        context: OfficialGribDecodeContext.Dwd,
        gridDefinitionTemplate: Int,
        geometry: DwdIconGridGeometry,
        values: DoubleArray,
    ): SelectedGribGridPoint {
        require(gridDefinitionTemplate == 101) { "DWD ICON field must decode as GDT 101" }
        require(geometry.modelRun == context.plan.modelRun) {
            "DWD decoded geometry belongs to a different model run"
        }
        require(geometry.modelRun == context.geometryPlan.modelRun) {
            "DWD decoded geometry does not match the validated geometry plan"
        }
        require(values.size == geometry.pointCount) {
            "DWD decoded value cardinality does not match CLAT/CLON geometry"
        }

        val index = nearestSphericalIndex(context.coordinate, geometry)
        val coordinate = geometry.coordinateAt(index)
        val value = values[index]
        require(value.isFinite()) { "DWD selected GRIB value must be finite" }
        return SelectedGribGridPoint(
            index = index,
            latitude = coordinate.latitude,
            longitudeDegreesEast = coordinate.longitudeDegreesEast,
            value = value,
        )
    }

    private fun nearestSphericalIndex(
        target: ForecastCoordinate,
        geometry: DwdIconGridGeometry,
    ): Int {
        val targetLatitude = Math.toRadians(target.latitude)
        val targetLongitude = Math.toRadians(target.longitude.toDegreesEast())
        val targetSinLatitude = sin(targetLatitude)
        val targetCosLatitude = cos(targetLatitude)

        var bestIndex = 0
        var bestScore = Double.NEGATIVE_INFINITY
        repeat(geometry.pointCount) { index ->
            val point = geometry.coordinateAt(index)
            val latitude = Math.toRadians(point.latitude)
            val longitude = Math.toRadians(point.longitudeDegreesEast)
            val score =
                targetSinLatitude * sin(latitude) +
                    targetCosLatitude * cos(latitude) * cos(longitude - targetLongitude)
            if (score > bestScore + SPHERICAL_TIE_EPSILON) {
                bestScore = score
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun requireCardinality(
        latitudes: DoubleArray,
        longitudes: DoubleArray,
        values: DoubleArray,
        expected: Int,
        provider: String,
    ) {
        require(latitudes.size == expected && longitudes.size == expected && values.size == expected) {
            "$provider decoded grid cardinality does not match the planned point request"
        }
        require(latitudes.all { it.isFinite() && it in -90.0..90.0 }) {
            "$provider decoded grid contains an invalid latitude"
        }
        require(longitudes.all { it.isFinite() }) {
            "$provider decoded grid contains an invalid longitude"
        }
    }

    private fun nearestBoundedIndex(
        position: Double,
        count: Int,
        label: String,
    ): Int {
        require(position >= -INDEX_TIE_EPSILON && position <= count - 1 + INDEX_TIE_EPSILON) {
            "$label target is outside the decoded grid extent"
        }
        val bounded = position.coerceIn(0.0, (count - 1).toDouble())
        val lower = floor(bounded).toInt()
        if (lower == count - 1) return lower
        val fraction = bounded - lower
        return if (fraction > 0.5 + INDEX_TIE_EPSILON) lower + 1 else lower
    }

    private fun nearestPeriodicIndex(
        position: Double,
        count: Int,
    ): Int {
        val lower = floor(position).toInt()
        val fraction = position - floor(position)
        val selected = if (fraction > 0.5 + INDEX_TIE_EPSILON) lower + 1 else lower
        return Math.floorMod(selected, count)
    }

    private fun Double.toDegreesEast(): Double = normalizeDegreesEast(this)

    private fun circularLongitudeDifference(first: Double, second: Double): Double {
        val difference = abs(normalizeDegreesEast(first) - normalizeDegreesEast(second))
        return minOf(difference, 360.0 - difference)
    }

    private fun normalizeDegreesEast(value: Double): Double {
        val normalized = ((value % 360.0) + 360.0) % 360.0
        return if (normalized == -0.0) 0.0 else normalized
    }
}
