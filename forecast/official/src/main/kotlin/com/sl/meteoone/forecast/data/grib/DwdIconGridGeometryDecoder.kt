package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.forecast.data.dwd.BoundedDwdBzip2Decompressor
import com.sl.meteoone.forecast.data.dwd.DwdIconGridGeometryPlan

private const val DWD_GEOMETRY_METADATA_LONG_COUNT = 35
private const val DWD_GEOMETRY_DOUBLE_COUNT = 4
private const val MAX_DWD_GEOMETRY_POINTS = 3_000_000
private const val GRIB_EDITION_2 = 2L
private const val DISCIPLINE_METEOROLOGICAL = 0L
private const val DWD_LOCAL_PARAMETER_CATEGORY = 191L
private const val PRODUCT_DEFINITION_TEMPLATE_INSTANTANEOUS = 0L
private const val GRID_DEFINITION_TEMPLATE_ICOSAHEDRAL = 101L

class DwdIconGridGeometryDecoder(
    private val decompressor: BoundedDwdBzip2Decompressor,
    private val nativeSession: EcCodesNativeSession,
) {
    fun decode(
        plan: DwdIconGridGeometryPlan,
        compressedLatitude: ByteArray,
        compressedLongitude: ByteArray,
    ): DwdIconGridGeometry =
        assemble(
            plan = plan,
            latitudes = decodeLatitude(compressedLatitude),
            longitudesDegreesEast = decodeLongitude(compressedLongitude),
        )

    fun decodeLatitude(compressed: ByteArray): DoubleArray =
        decodeCoordinateField(
            compressed = compressed,
            field = GeometryField.LATITUDE,
        )

    fun decodeLongitude(compressed: ByteArray): DoubleArray {
        val values = decodeCoordinateField(
            compressed = compressed,
            field = GeometryField.LONGITUDE,
        )
        return DoubleArray(values.size) { index ->
            normalizeLongitude(values[index])
        }
    }

    fun assemble(
        plan: DwdIconGridGeometryPlan,
        latitudes: DoubleArray,
        longitudesDegreesEast: DoubleArray,
    ): DwdIconGridGeometry {
        require(latitudes.size == longitudesDegreesEast.size) {
            "DWD ICON CLAT/CLON cardinality mismatch"
        }
        return DwdIconGridGeometry(
            modelRun = plan.modelRun,
            latitudes = latitudes,
            longitudesDegreesEast = longitudesDegreesEast,
        )
    }

    private fun decodeCoordinateField(
        compressed: ByteArray,
        field: GeometryField,
    ): DoubleArray {
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
            require(values.all(field.isValidValue)) {
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
        require(message.metadata[0] == GRIB_EDITION_2)
        require(message.metadata[1] == DISCIPLINE_METEOROLOGICAL)
        require(message.metadata[2] == DWD_LOCAL_PARAMETER_CATEGORY)
        require(message.metadata[3] == field.parameterNumber)
        require(message.metadata[4] == PRODUCT_DEFINITION_TEMPLATE_INSTANTANEOUS)
        require(message.metadata[5] == GRID_DEFINITION_TEMPLATE_ICOSAHEDRAL)
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
