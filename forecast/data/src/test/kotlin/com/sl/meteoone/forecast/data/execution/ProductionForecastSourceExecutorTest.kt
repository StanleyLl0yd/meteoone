package com.sl.meteoone.forecast.data.execution

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResponse
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import com.sl.meteoone.forecast.data.grib.DecodedGribField
import com.sl.meteoone.forecast.data.grib.GribForecastParameter
import com.sl.meteoone.forecast.data.grib.GribDecodeRequest
import com.sl.meteoone.forecast.data.grib.GribFieldDecoder
import com.sl.meteoone.forecast.data.grib.GribValueUnit
import com.sl.meteoone.forecast.data.grib.OfficialGribDecodeContext
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoModel
import com.sl.meteoone.forecast.data.transport.ForecastHttpAdapter
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProductionForecastSourceExecutorTest {
    private val coordinate = ForecastCoordinate(latitude = 59.9, longitude = 30.3)
    private val location = ForecastLocation(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        elevationMeters = 20,
        timeZoneId = "Europe/Moscow",
    )
    private val generatedAt = Instant.parse("2026-09-14T12:30:00Z")
    private val modelRun = Instant.parse("2026-09-14T00:00:00Z")

    @Test
    fun executesAllModelSpecificOpenMeteoRequestsAndExact72HourMapper() {
        val transport = RecordingTransport { request ->
            assertEquals("api.open-meteo.com", request.uri.host)
            success(openMeteoPayload().toByteArray())
        }
        val executor = executor(transport)
        val expected = listOf(
            OpenMeteoModel.NOAA_GFS_GLOBAL to ModelFamily.NOAA_GFS,
            OpenMeteoModel.ECMWF_IFS to ModelFamily.ECMWF_IFS,
            OpenMeteoModel.DWD_ICON_GLOBAL to ModelFamily.DWD_ICON,
        )

        val forecasts = expected.map { (model, _) ->
            executor.openMeteo(
                model = model,
                coordinate = coordinate,
                location = location,
                generatedAt = generatedAt,
            )
        }

        forecasts.zip(expected).forEach { (forecast, expectedPath) ->
            assertEquals(ForecastProvider.OPEN_METEO, forecast.origin.provider)
            assertEquals(expectedPath.second, forecast.origin.modelFamily)
            assertEquals(72, forecast.hourly.size)
        }
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests[0].uri.rawQuery.contains("models=ncep_gfs_global"))
        assertTrue(transport.requests[1].uri.rawQuery.contains("models=ecmwf_ifs"))
        assertTrue(transport.requests[2].uri.rawQuery.contains("models=icon_global"))
    }

    @Test
    fun executesNoaaBoundedHttpDecodeAndMapper() {
        val decoder = RecordingDecoder()
        val transport = RecordingTransport { request ->
            assertEquals("nomads.ncep.noaa.gov", request.uri.host)
            success(byteArrayOf(1, 2, 3))
        }
        val executor = executor(transport, decoder)

        val forecast = executor.noaa(
            modelRun = modelRun,
            forecastHour = 13,
            coordinate = coordinate,
            location = location,
            generatedAt = generatedAt,
        )

        assertEquals(ForecastProvider.NOAA_NOMADS, forecast.origin.provider)
        assertEquals(ModelFamily.NOAA_GFS, forecast.origin.modelFamily)
        assertEquals(modelRun.plusSeconds(13 * 3600L), forecast.hourly.single().time)
        assertIs<OfficialGribDecodeContext.Noaa>(decoder.requests.single().context)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun executesEcmwfIndexExactRangeDecodeAndMapper() {
        val decoder = RecordingDecoder()
        val index = ecmwfIndexLine(step = 15, offset = 0, length = 4)
        val transport = RecordingTransport { request ->
            if (request.uri.path.endsWith(".index")) {
                success(index.toByteArray())
            } else {
                assertEquals("bytes=0-3", request.headers.getValue("Range"))
                assertEquals("identity", request.headers.getValue("Accept-Encoding"))
                success(
                    body = byteArrayOf(7, 8, 9, 10),
                    statusCode = 206,
                    headers = mapOf("Content-Range" to listOf("bytes 0-3/100")),
                )
            }
        }
        val executor = executor(transport, decoder)

        val forecast = executor.ecmwf(
            modelRun = modelRun,
            forecastHour = 15,
            coordinate = coordinate,
            location = location,
            generatedAt = generatedAt,
        )

        assertEquals(ForecastProvider.ECMWF_OPEN_DATA, forecast.origin.provider)
        assertEquals(modelRun.plusSeconds(15 * 3600L), forecast.hourly.single().time)
        assertIs<OfficialGribDecodeContext.Ecmwf>(decoder.requests.single().context)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun executesOneDwdFieldOnlyAndCarriesRunBoundGeometryIntoDecoder() {
        val decoder = RecordingDecoder()
        val compressed = byteArrayOf(1, 3, 5, 7)
        val decompressed = byteArrayOf(2, 4, 6, 8)
        var decompressionCalls = 0
        val transport = RecordingTransport { request ->
            assertEquals("opendata.dwd.de", request.uri.host)
            assertTrue(request.uri.path.contains("/t_2m/"))
            success(compressed)
        }
        val executor = ProductionForecastSourceExecutor(
            httpAdapter = ForecastHttpAdapter(transport),
            gribDecoder = decoder,
            dwdDecompress = { payload ->
                decompressionCalls += 1
                assertContentEquals(compressed, payload)
                decompressed
            },
        )

        val forecast = executor.dwd(
            modelRun = modelRun,
            forecastHour = 13,
            coordinate = coordinate,
            location = location,
            generatedAt = generatedAt,
        )

        assertEquals(ForecastProvider.DWD_OPEN_DATA, forecast.origin.provider)
        assertEquals(ModelFamily.DWD_ICON, forecast.origin.modelFamily)
        assertEquals(1, decompressionCalls)
        assertEquals(1, transport.requests.size)
        assertContentEquals(decompressed, decoder.requests.single().payload)
        val context = assertIs<OfficialGribDecodeContext.Dwd>(decoder.requests.single().context)
        assertEquals(modelRun, context.geometryPlan.modelRun)
        assertEquals(modelRun.plusSeconds(13 * 3600L), context.validTime)
    }

    private fun executor(
        transport: BoundedHttpsTransport,
        decoder: GribFieldDecoder = RecordingDecoder(),
    ) = ProductionForecastSourceExecutor(
        httpAdapter = ForecastHttpAdapter(transport),
        gribDecoder = decoder,
        dwdDecompress = { it },
    )

    private class RecordingDecoder : GribFieldDecoder {
        val requests = mutableListOf<GribDecodeRequest>()

        override fun decode(request: GribDecodeRequest): List<DecodedGribField> {
            requests += request
            return listOf(
                DecodedGribField(
                    parameter = GribForecastParameter.TEMPERATURE_2M,
                    value = 283.15,
                    unit = GribValueUnit.KELVIN,
                    validTime = request.context.validTime,
                ),
            )
        }
    }

    private class RecordingTransport(
        private val responder: (BoundedHttpsRequest) -> BoundedHttpsResult,
    ) : BoundedHttpsTransport {
        val requests = mutableListOf<BoundedHttpsRequest>()

        override fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall {
            requests += request
            return object : BoundedHttpsCall {
                override fun execute(): BoundedHttpsResult = responder(request)

                override fun cancel() = Unit
            }
        }
    }

    private fun success(
        body: ByteArray,
        statusCode: Int = 200,
        headers: Map<String, List<String>> = emptyMap(),
    ): BoundedHttpsResult = BoundedHttpsResult.Success(
        BoundedHttpsResponse(
            statusCode = statusCode,
            headers = headers,
            body = body,
        ),
    )

    private fun ecmwfIndexLine(
        step: Int,
        offset: Long,
        length: Long,
    ): String =
        """{"domain":"g","date":"20260914","time":"0000","class":"od","type":"fc","stream":"oper","step":"$step","levtype":"sfc","param":"2t","_offset":$offset,"_length":$length}"""

    private fun openMeteoPayload(): String {
        val first = Instant.parse("2026-09-14T13:00:00Z").epochSecond
        val times = List(72) { index -> first + index * 3600L }.joinToString(",")
        val temperatures = List(72) { "10.0" }.joinToString(",")
        return """
            {
              "latitude": 59.9,
              "longitude": 30.3,
              "utc_offset_seconds": 0,
              "hourly_units": {
                "time": "unixtime",
                "temperature_2m": "°C"
              },
              "hourly": {
                "time": [$times],
                "temperature_2m": [$temperatures]
              }
            }
        """.trimIndent()
    }
}
