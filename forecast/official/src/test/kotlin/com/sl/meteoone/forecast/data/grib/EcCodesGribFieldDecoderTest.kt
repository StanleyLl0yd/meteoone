package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.forecast.data.noaa.NoaaGfsRequestPlanner
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EcCodesGribFieldDecoderTest {
    private val modelRun = Instant.parse("2026-09-10T18:00:00Z")
    private val plan = NoaaGfsRequestPlanner.plan(
        modelRun = modelRun,
        coordinate = ForecastCoordinate(latitude = 48.8, longitude = 2.3),
        forecastHour = 6,
    )

    @Test
    fun `native NOAA point flows through selection and semantic binding`() {
        var observedMaxMessages = -1
        var observedMaxValues = -1
        val session = EcCodesNativeSession { _, maxMessages, maxTotalValues ->
            observedMaxMessages = maxMessages
            observedMaxValues = maxTotalValues
            arrayOf(temperatureMessage(281.25))
        }
        val decoder = EcCodesGribFieldDecoder(
            nativeSession = session,
            dwdGeometryProvider = DwdIconGridGeometryProvider { error("DWD geometry must not be requested") },
        )

        val fields = decoder.decode(GribDecodeRequest.noaa(byteArrayOf(1), plan))

        assertEquals(64, observedMaxMessages)
        assertEquals(64, observedMaxValues)
        assertEquals(1, fields.size)
        assertEquals(GribForecastParameter.TEMPERATURE_2M, fields.single().parameter)
        assertEquals(GribValueUnit.KELVIN, fields.single().unit)
        assertEquals(281.25, fields.single().value)
        assertEquals(Instant.parse("2026-09-11T00:00:00Z"), fields.single().validTime)
    }

    @Test
    fun `native metadata layout drift fails closed`() {
        val malformed = temperatureMessage(281.25).let { message ->
            NativeGribMessage(
                metadata = message.metadata.copyOf(message.metadata.size - 1),
                geometry = message.geometry,
                values = message.values,
            )
        }
        val decoder = EcCodesGribFieldDecoder(
            nativeSession = EcCodesNativeSession { _, _, _ -> arrayOf(malformed) },
            dwdGeometryProvider = DwdIconGridGeometryProvider { error("DWD geometry must not be requested") },
        )

        assertFailsWith<IllegalArgumentException> {
            decoder.decode(GribDecodeRequest.noaa(byteArrayOf(1), plan))
        }
    }

    @Test
    fun `native value cardinality contradiction fails closed`() {
        val malformed = temperatureMessage(281.25).also { message ->
            message.metadata[28] = 2
        }
        val decoder = EcCodesGribFieldDecoder(
            nativeSession = EcCodesNativeSession { _, _, _ -> arrayOf(malformed) },
            dwdGeometryProvider = DwdIconGridGeometryProvider { error("DWD geometry must not be requested") },
        )

        assertFailsWith<IllegalArgumentException> {
            decoder.decode(GribDecodeRequest.noaa(byteArrayOf(1), plan))
        }
    }

    private fun temperatureMessage(value: Double): NativeGribMessage {
        val metadata = LongArray(35) { Long.MIN_VALUE }
        metadata[0] = 2
        metadata[1] = 0
        metadata[2] = 0
        metadata[3] = 0
        metadata[4] = 0
        metadata[5] = 0
        metadata[6] = 0
        metadata[7] = 2026
        metadata[8] = 9
        metadata[9] = 10
        metadata[10] = 18
        metadata[11] = 0
        metadata[12] = 0
        metadata[13] = 1
        metadata[14] = 6
        metadata[15] = 103
        metadata[16] = 0
        metadata[17] = 2
        metadata[28] = 1
        return NativeGribMessage(
            metadata = metadata,
            geometry = doubleArrayOf(
                plan.gridPoint.latitude,
                plan.gridPoint.longitudeDegreesEast,
                Double.NaN,
                Double.NaN,
            ),
            values = doubleArrayOf(value),
        )
    }
}
