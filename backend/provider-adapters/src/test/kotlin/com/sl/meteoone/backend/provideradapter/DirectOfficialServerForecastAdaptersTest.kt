package com.sl.meteoone.backend.provideradapter

import com.sl.meteoone.backend.provider.ProviderGateway
import com.sl.meteoone.backend.provider.ProviderGatewayFailureReason
import com.sl.meteoone.backend.provider.ProviderGatewayRequest
import com.sl.meteoone.backend.provider.ProviderGatewayResponse
import com.sl.meteoone.backend.provider.ProviderGatewayResult
import com.sl.meteoone.backend.provider.ProviderResponseValidator
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.data.grib.EcCodesNativeSession
import com.sl.meteoone.forecast.data.grib.NativeGribMessage
import com.sl.meteoone.forecast.data.noaa.NoaaGfsRequestPlanner
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DirectOfficialServerForecastAdaptersTest {
    private val coordinate = ForecastCoordinate(59.9, 30.3)
    private val location = ForecastLocation(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        elevationMeters = 12,
        timeZoneId = "Europe/Moscow",
    )
    private val modelRun = Instant.parse("2026-09-24T06:00:00Z")
    private val generatedAt = Instant.parse("2026-09-24T12:30:00Z")
    private val forecastHour = 6

    @Test
    fun executesNoaaEcmwfAndDwdThroughOfficialGatewayContracts() = runBlocking {
        val gateway = FixtureGateway()
        val adapter = adapters(gateway)

        val noaa = assertIs<ServerForecastAdapterResult.Success>(
            adapter.fetchNoaa(
                modelRun = modelRun,
                forecastHour = forecastHour,
                coordinate = coordinate,
                location = location,
                generatedAt = generatedAt,
            ),
        )
        val ecmwf = assertIs<ServerForecastAdapterResult.Success>(
            adapter.fetchEcmwf(
                modelRun = modelRun,
                forecastHour = forecastHour,
                coordinate = coordinate,
                location = location,
                generatedAt = generatedAt,
            ),
        )
        val dwd = assertIs<ServerForecastAdapterResult.Success>(
            adapter.fetchDwd(
                modelRun = modelRun,
                forecastHour = forecastHour,
                coordinate = coordinate,
                location = location,
                generatedAt = generatedAt,
            ),
        )

        assertEquals(ForecastProvider.NOAA_NOMADS, noaa.forecast.origin.provider)
        assertEquals(ModelFamily.NOAA_GFS, noaa.forecast.origin.modelFamily)
        assertEquals(modelRun, noaa.forecast.origin.modelRun)

        assertEquals(ForecastProvider.ECMWF_OPEN_DATA, ecmwf.forecast.origin.provider)
        assertEquals(ModelFamily.ECMWF_IFS, ecmwf.forecast.origin.modelFamily)
        assertEquals(modelRun, ecmwf.forecast.origin.modelRun)

        assertEquals(ForecastProvider.DWD_OPEN_DATA, dwd.forecast.origin.provider)
        assertEquals(ModelFamily.DWD_ICON, dwd.forecast.origin.modelFamily)
        assertEquals(modelRun, dwd.forecast.origin.modelRun)

        val ecmwfRange = gateway.requests.single { it.byteRange != null }
        assertEquals(ForecastProvider.ECMWF_OPEN_DATA, ecmwfRange.provider)
        assertEquals(ModelFamily.ECMWF_IFS, ecmwfRange.modelFamily)
        assertEquals(100L, assertNotNull(ecmwfRange.byteRange).offset)
        assertEquals(5L, ecmwfRange.byteRange?.length)
        assertEquals(setOf(206), ecmwfRange.expectedStatusCodes)

        assertEquals(
            3,
            gateway.requests.count { it.provider == ForecastProvider.DWD_OPEN_DATA },
        )
        assertTrue(
            gateway.requests
                .filter { it.provider == ForecastProvider.DWD_OPEN_DATA }
                .all { it.modelFamily == ModelFamily.DWD_ICON },
        )
    }

    @Test
    fun dwdGeometryIsSingleFlightRunScopedAndReusedForSameRun() = runBlocking {
        val gateway = FixtureGateway()
        val adapter = adapters(gateway)

        repeat(2) {
            assertIs<ServerForecastAdapterResult.Success>(
                adapter.fetchDwd(
                    modelRun = modelRun,
                    forecastHour = forecastHour,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                ),
            )
        }

        val dwdRequests = gateway.requests.filter {
            it.provider == ForecastProvider.DWD_OPEN_DATA
        }
        assertEquals(4, dwdRequests.size)
        assertEquals(1, dwdRequests.count { "/clat/" in it.uri.path })
        assertEquals(1, dwdRequests.count { "/clon/" in it.uri.path })
        assertEquals(2, dwdRequests.count { "/t_2m/" in it.uri.path })
    }

    @Test
    fun semanticDecodeFailureIsReportedAsInvalidProviderResponse() = runBlocking {
        val gateway = FixtureGateway()
        val adapter = DirectOfficialServerForecastAdapters(
            gateway = gateway,
            nativeSession = EcCodesNativeSession { payload, _, _ ->
                if (payload.decodeToString() == "noaa") {
                    arrayOf(
                        NativeGribMessage(
                            metadata = LongArray(1),
                            geometry = DoubleArray(4),
                            values = doubleArrayOf(280.0),
                        ),
                    )
                } else {
                    nativeMessages(payload)
                }
            },
        )

        val result = adapter.fetchNoaa(
            modelRun = modelRun,
            forecastHour = forecastHour,
            coordinate = coordinate,
            location = location,
            generatedAt = generatedAt,
        )

        val failure = assertIs<ServerForecastAdapterResult.Failure>(result)
        assertEquals(ForecastProvider.NOAA_NOMADS, failure.provider)
        assertEquals(ModelFamily.NOAA_GFS, failure.modelFamily)
        assertEquals(ServerForecastAdapterFailureReason.INVALID_RESPONSE, failure.reason)
    }

    @Test
    fun rejectsLocationCoordinateMismatchBeforeGateway() = runBlocking {
        val gateway = FixtureGateway()
        val adapter = adapters(gateway)

        assertFailsWith<IllegalArgumentException> {
            adapter.fetchNoaa(
                modelRun = modelRun,
                forecastHour = forecastHour,
                coordinate = coordinate,
                location = location.copy(latitude = 59.8),
                generatedAt = generatedAt,
            )
        }
        assertTrue(gateway.requests.isEmpty())
    }

    private fun adapters(gateway: ProviderGateway): DirectOfficialServerForecastAdapters =
        DirectOfficialServerForecastAdapters(
            gateway = gateway,
            nativeSession = EcCodesNativeSession { payload, _, _ ->
                nativeMessages(payload)
            },
        )

    private fun nativeMessages(payload: ByteArray): Array<NativeGribMessage> =
        when (payload.decodeToString()) {
            "noaa" -> arrayOf(noaaTemperature())
            "ecmwf" -> arrayOf(ecmwfTemperature())
            "lat" -> arrayOf(dwdCoordinate(parameterNumber = 1, values = doubleArrayOf(59.9, 0.0)))
            "lon" -> arrayOf(dwdCoordinate(parameterNumber = 2, values = doubleArrayOf(30.3, 0.0)))
            "dwd-field" -> arrayOf(dwdTemperature())
            else -> error("Unexpected native fixture payload: ${payload.decodeToString()}")
        }

    private fun noaaTemperature(): NativeGribMessage {
        val plan = NoaaGfsRequestPlanner.plan(
            modelRun = modelRun,
            coordinate = coordinate,
            forecastHour = forecastHour,
        )
        return NativeGribMessage(
            metadata = temperatureMetadata(
                dataRepresentationTemplate = 0,
                gridDefinitionTemplate = 0,
                valueCount = 1,
            ),
            geometry = doubleArrayOf(
                plan.gridPoint.latitude,
                plan.gridPoint.longitudeDegreesEast,
                Double.NaN,
                Double.NaN,
            ),
            values = doubleArrayOf(280.0),
        )
    }

    private fun ecmwfTemperature(): NativeGribMessage {
        val metadata = temperatureMetadata(
            dataRepresentationTemplate = 42,
            gridDefinitionTemplate = 0,
            valueCount = 4,
        )
        metadata[29] = 2
        metadata[30] = 2
        metadata[31] = 0
        metadata[32] = 0
        metadata[33] = 0
        metadata[34] = 0
        return NativeGribMessage(
            metadata = metadata,
            geometry = doubleArrayOf(60.0, 0.0, 180.0, 1.0),
            values = doubleArrayOf(281.0, 282.0, 283.0, 284.0),
        )
    }

    private fun dwdTemperature(): NativeGribMessage =
        NativeGribMessage(
            metadata = temperatureMetadata(
                dataRepresentationTemplate = 42,
                gridDefinitionTemplate = 101,
                valueCount = 2,
            ),
            geometry = DoubleArray(4) { Double.NaN },
            values = doubleArrayOf(279.0, 290.0),
        )

    private fun temperatureMetadata(
        dataRepresentationTemplate: Int,
        gridDefinitionTemplate: Int,
        valueCount: Int,
    ): LongArray {
        val metadata = LongArray(35) { Long.MIN_VALUE }
        val run = modelRun.atOffset(java.time.ZoneOffset.UTC)
        metadata[0] = 2
        metadata[1] = 0
        metadata[2] = 0
        metadata[3] = 0
        metadata[4] = 0
        metadata[5] = gridDefinitionTemplate.toLong()
        metadata[6] = dataRepresentationTemplate.toLong()
        metadata[7] = run.year.toLong()
        metadata[8] = run.monthValue.toLong()
        metadata[9] = run.dayOfMonth.toLong()
        metadata[10] = run.hour.toLong()
        metadata[11] = 0
        metadata[12] = 0
        metadata[13] = 1
        metadata[14] = forecastHour.toLong()
        metadata[15] = 103
        metadata[16] = 0
        metadata[17] = 2
        metadata[28] = valueCount.toLong()
        return metadata
    }

    private fun dwdCoordinate(
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

    private inner class FixtureGateway : ProviderGateway {
        val requests = mutableListOf<ProviderGatewayRequest>()

        override suspend fun execute(
            request: ProviderGatewayRequest,
            responseValidator: ProviderResponseValidator,
        ): ProviderGatewayResult {
            requests += request
            val response = fixtureResponse(request)
            return if (responseValidator.isValid(response)) {
                ProviderGatewayResult.Success(response)
            } else {
                ProviderGatewayResult.Failure(
                    provider = request.provider,
                    modelFamily = request.modelFamily,
                    reason = ProviderGatewayFailureReason.INVALID_RESPONSE,
                )
            }
        }

        private fun fixtureResponse(request: ProviderGatewayRequest): ProviderGatewayResponse {
            val status: Int
            val body: ByteArray
            val headers: Map<String, List<String>>

            when {
                request.provider == ForecastProvider.NOAA_NOMADS -> {
                    status = 200
                    body = "noaa".encodeToByteArray()
                    headers = emptyMap()
                }

                request.provider == ForecastProvider.ECMWF_OPEN_DATA &&
                    request.uri.path.endsWith(".index") -> {
                    status = 200
                    body = ecmwfIndex().encodeToByteArray()
                    headers = mapOf("Content-Type" to listOf("application/json"))
                }

                request.provider == ForecastProvider.ECMWF_OPEN_DATA -> {
                    status = 206
                    body = "ecmwf".encodeToByteArray()
                    val range = assertNotNull(request.byteRange)
                    headers = mapOf(
                        "Content-Range" to listOf(
                            "bytes ${range.offset}-${range.inclusiveEnd}/1000",
                        ),
                    )
                }

                request.provider == ForecastProvider.DWD_OPEN_DATA &&
                    "/clat/" in request.uri.path -> {
                    status = 200
                    body = bzip2("lat")
                    headers = emptyMap()
                }

                request.provider == ForecastProvider.DWD_OPEN_DATA &&
                    "/clon/" in request.uri.path -> {
                    status = 200
                    body = bzip2("lon")
                    headers = emptyMap()
                }

                request.provider == ForecastProvider.DWD_OPEN_DATA -> {
                    status = 200
                    body = bzip2("dwd-field")
                    headers = emptyMap()
                }

                else -> error("Unexpected provider fixture request")
            }

            return ProviderGatewayResponse(
                provider = request.provider,
                modelFamily = request.modelFamily,
                statusCode = status,
                headers = headers,
                body = body,
            )
        }
    }

    private fun ecmwfIndex(): String =
        """{"domain":"g","date":"20260924","time":"0600","class":"od","type":"fc","stream":"oper","step":"6","levtype":"sfc","param":"2t","_offset":100,"_length":5}"""

    private fun bzip2(value: String): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { compressed ->
            compressed.write(value.encodeToByteArray())
        }
        return output.toByteArray()
    }
}
