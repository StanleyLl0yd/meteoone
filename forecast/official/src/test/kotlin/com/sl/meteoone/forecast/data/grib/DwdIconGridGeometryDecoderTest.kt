package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.forecast.data.dwd.BoundedDwdBzip2Decompressor
import com.sl.meteoone.forecast.data.dwd.DwdIconGridGeometryPlanner
import java.io.ByteArrayOutputStream
import java.time.Instant
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DwdIconGridGeometryDecoderTest {
    private val plan = DwdIconGridGeometryPlanner.plan(
        Instant.parse("2026-09-10T18:00:00Z"),
    )

    @Test
    fun decodesAndNormalizesRunBoundGeometry() {
        val decoder = decoder(
            latitude = coordinateMessage(1, doubleArrayOf(10.0, -20.0)),
            longitude = coordinateMessage(2, doubleArrayOf(-10.0, 200.0)),
        )

        val geometry = decoder.decode(
            plan = plan,
            compressedLatitude = bzip2("latitude"),
            compressedLongitude = bzip2("longitude"),
        )

        assertEquals(plan.modelRun, geometry.modelRun)
        assertEquals(2, geometry.pointCount)
        assertEquals(10.0, geometry.coordinateAt(0).latitude)
        assertEquals(350.0, geometry.coordinateAt(0).longitudeDegreesEast)
        assertEquals(-20.0, geometry.coordinateAt(1).latitude)
        assertEquals(200.0, geometry.coordinateAt(1).longitudeDegreesEast)
    }

    @Test
    fun signatureCardinalityAndNonFiniteDriftFailClosed() {
        val badSignature = decoder(
            latitude = coordinateMessage(2, doubleArrayOf(10.0)),
            longitude = coordinateMessage(2, doubleArrayOf(20.0)),
        )
        assertFailsWith<IllegalArgumentException> {
            badSignature.decode(plan, bzip2("latitude"), bzip2("longitude"))
        }

        val mismatch = decoder(
            latitude = coordinateMessage(1, doubleArrayOf(10.0, 11.0)),
            longitude = coordinateMessage(2, doubleArrayOf(20.0)),
        )
        assertFailsWith<IllegalArgumentException> {
            mismatch.decode(plan, bzip2("latitude"), bzip2("longitude"))
        }

        val nonFinite = decoder(
            latitude = coordinateMessage(1, doubleArrayOf(Double.NaN)),
            longitude = coordinateMessage(2, doubleArrayOf(20.0)),
        )
        assertFailsWith<IllegalArgumentException> {
            nonFinite.decode(plan, bzip2("latitude"), bzip2("longitude"))
        }
    }

    private fun decoder(
        latitude: NativeGribMessage,
        longitude: NativeGribMessage,
    ): DwdIconGridGeometryDecoder =
        DwdIconGridGeometryDecoder(
            decompressor = BoundedDwdBzip2Decompressor(),
            nativeSession = EcCodesNativeSession { payload, maxMessages, maxValues ->
                assertEquals(1, maxMessages)
                assertEquals(3_000_000, maxValues)
                when (payload.decodeToString()) {
                    "latitude" -> arrayOf(latitude)
                    "longitude" -> arrayOf(longitude)
                    else -> error("Unexpected fixture")
                }
            },
        )

    private fun coordinateMessage(
        parameterNumber: Int,
        values: DoubleArray,
    ): NativeGribMessage {
        val metadata = LongArray(35) { Long.MIN_VALUE }
        metadata[0] = 2
        metadata[1] = 0
        metadata[2] = 191
        metadata[3] = parameterNumber.toLong()
        metadata[4] = 0
        metadata[5] = 101
        metadata[6] = 42
        metadata[28] = values.size.toLong()
        return NativeGribMessage(
            metadata = metadata,
            geometry = DoubleArray(4) { Double.NaN },
            values = values,
        )
    }

    private fun bzip2(value: String): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { compressed ->
            compressed.write(value.encodeToByteArray())
        }
        return output.toByteArray()
    }
}
