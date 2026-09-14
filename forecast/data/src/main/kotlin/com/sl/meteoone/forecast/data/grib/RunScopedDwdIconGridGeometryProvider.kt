package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.forecast.data.dwd.BoundedDwdBzip2Decompressor
import com.sl.meteoone.forecast.data.dwd.DwdIconGridGeometryPlan
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import com.sl.meteoone.forecast.data.transport.ForecastHttpAdapter

private const val DWD_GEOMETRY_METADATA_LONG_COUNT = 35
private const val DWD_GEOMETRY_DOUBLE_COUNT = 4
private const val MAX_DWD_GEOMETRY_POINTS = 3_000_000
private const val GRIB_EDITION_2 = 2L
private const val DISCIPLINE_METEOROLOGICAL = 0L
private const val DWD_LOCAL_PARAMETER_CATEGORY = 191L
private const val PRODUCT_DEFINITION_TEMPLATE_INSTANTANEOUS = 0L
private const val GRID_DEFINITION_TEMPLATE_ICOSAHEDRAL = 101L

/**
 * Loads DWD ICON CLAT/CLON geometry for exactly one validated model run at a time.
 *
 * The cache is deliberately process-memory only. A geometry is published only after
 * both coordinate fields have been fetched, decompressed, decoded and validated for
 * the same [DwdIconGridGeometryPlan]. A cached geometry is never returned for another
 * model run.
 */
internal class RunScopedDwdIconGridGeometryProvider(
    private val httpAdapter: ForecastHttpAdapter,
    private val decompressor: BoundedDwdBzip2Decompressor,
    private val nativeSession: EcCodesNativeSession,
) : DwdIconGridGeometryProvider {
    private val loadLock = Any()

    @Volatile
    private var cachedGeometry: DwdIconGridGeometry? = null

    override fun geometryFor(plan: DwdIconGridGeometryPlan): DwdIconGridGeometry {
        cachedGeometry?.takeIf { it.modelRun == plan.modelRun }?.let { return it }

        return synchronized(loadLock) {
            cachedGeometry?.takeIf { it.modelRun == plan.modelRun }?.let { return@synchronized it }

            val latitudes = decodeCoordinateField(
                request = plan.latitudeRequest,
                field = GeometryField.LATITUDE,
            )
            val longitudes = decodeCoordinateField(
                request = plan.longitudeRequest,
                field = GeometryField.LONGITUDE,
            )
            require(latitudes.size == longitudes.size) {
                "DWD ICON CLAT/CLON cardinality mismatch"
            }

            val normalizedLongitudes = DoubleArray(longitudes.size) { index ->
                normalizeLongitude(longitudes[index])
            }
            DwdIconGridGeometry(
                modelRun = plan.modelRun,
                latitudes = latitudes,
                longitudesDegreesEast = normalizedLongitudes,
            ).also { cachedGeometry = it }
        }
    }

    private fun decodeCoordinateField(
        request: OfficialSourceRequest,
        field: GeometryField,
    ): DoubleArray {
        val compressed = when (val result = httpAdapter.newOrdinaryCall(request).execute()) {
            is BoundedHttpsResult.Success -> result.response.body
            is BoundedHttpsResult.Failure -> throw IllegalStateException(
                "DWD ICON ${field.label} transport failed: ${result.reason}",
            )
        }
        val payload = decompressor.decompress(compressed)
        val messages = nativeSession.decode(
            payload = payload,
            maxMessages = 1,
            maxTotalValues = MAX_DWD_GEOMETRY_POINTS,
        )
        require(messages.size == 1) {
            "DWD ICON ${field.label} must contain exactly one GRIB message"
        }

        val message = messages.single()
        validateCoordinateMessage(message, field)
        return message.values.also { values ->
            require(values.all(field::isValidValue)) {
                "DWD ICON ${field.label} contains an invalid coordinate"
            }
        }
    }

    private fun validateCoordinateMessage(
        message: NativeGribMessage,
        field: GeometryField,
    ) {
        require(message.metadata.size == DWD_GEOMETRY_METADATA_LONG_COUNT) {
            "Native DWD geometry metadata layout does not match the Kotlin boundary"
        }
        require(message.geometry.size == DWD_GEOMETRY_DOUBLE_COUNT) {
            "Native DWD geometry layout does not match the Kotlin boundary"
        }
        require(message.values.size in 1..MAX_DWD_GEOMETRY_POINTS) {
            "DWD ICON ${field.label} cardinality is outside the bounded grid limit"
        }
        require(message.metadata[28] == message.values.size.toLong()) {
            "DWD ICON ${field.label} value cardinality metadata is inconsistent"
        }
        require(message.metadata[0] == GRIB_EDITION_2) {
            "DWD ICON ${field.label} must be GRIB edition 2"
        }
        require(message.metadata[1] == DISCIPLINE_METEOROLOGICAL) {
            "DWD ICON ${field.label} discipline is invalid"
        }
        require(message.metadata[2] == DWD_LOCAL_PARAMETER_CATEGORY) {
            "DWD ICON ${field.label} parameter category is invalid"
        }
        require(message.metadata[3] == field.parameterNumber) {
            "DWD ICON ${field.label} parameter number is invalid"
        }
        require(message.metadata[4] == PRODUCT_DEFINITION_TEMPLATE_INSTANTANEOUS) {
            "DWD ICON ${field.label} must use the instantaneous product template"
        }
        require(message.metadata[5] == GRID_DEFINITION_TEMPLATE_ICOSAHEDRAL) {
            "DWD ICON ${field.label} must use GDT 101"
        }
    }

    private fun normalizeLongitude(value: Double): Double {
        require(value.isFinite() && value in -180.0..360.0) {
            "DWD ICON CLON contains an invalid longitude"
        }
        val normalized = ((value % 360.0) + 360.0) % 360.0
        return if (normalized == -0.0) 0.0 else normalized
    }

    private enum class GeometryField(
        val label: String,
        val parameterNumber: Long,
        val isValidValue: (Double) -> Boolean,
    ) {
        LATITUDE(
            label = "CLAT",
            parameterNumber = 1L,
            isValidValue = { value -> value.isFinite() && value in -90.0..90.0 },
        ),
        LONGITUDE(
            label = "CLON",
            parameterNumber = 2L,
            isValidValue = { value -> value.isFinite() && value in -180.0..360.0 },
        ),
    }
}
