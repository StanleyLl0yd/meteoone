package com.sl.meteoone.forecast.repository

import com.sl.meteoone.backend.contract.ForecastResponseDto
import com.sl.meteoone.backend.contract.ForecastSourceIdentityValue
import com.sl.meteoone.backend.contract.ForecastWireJson
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.model.WeatherCondition
import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsFailureReason
import com.sl.meteoone.core.network.BoundedHttpsMethod
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResponse
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import java.net.URI
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackendForecastRefreshSourceTest {
    @Test
    fun endpointRequiresExactHttpsForecastPath() {
        BackendForecastEndpoint.parse("https://api.example/v1/forecast")

        listOf(
            "http://api.example/v1/forecast",
            "https://user@api.example/v1/forecast",
            "https://api.example:8443/v1/forecast",
            "https://api.example/v1/forecast?raw=1",
            "https://api.example/other",
        ).forEach { raw ->
            assertFailsWith<IllegalArgumentException> {
                BackendForecastEndpoint.parse(raw)
            }
        }
    }

    @Test
    fun requestUsesPrivacyReducedIntegerTenthsAndStrictPostBoundary() = runBlocking {
        val transport = RecordingTransport(successResponse())
        val source = source(transport)

        val result = source.forecast(
            coordinate = ForecastCoordinate(59.9, 30.3),
            elevationMeters = 15,
            timeZoneId = "Europe/Moscow",
            generatedAt = Instant.parse("2026-10-01T10:00:00Z"),
        )

        assertIs<ForecastRefreshSourceResult.Available>(result)
        val request = transport.requests.single()
        assertEquals(BoundedHttpsMethod.POST, request.method)
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("application/json", request.headers["Accept"])
        val raw = requireNotNull(request.body).decodeToString()
        assertTrue(raw.contains("\"latitudeTenths\":599"))
        assertTrue(raw.contains("\"longitudeTenths\":303"))
        assertTrue(!raw.contains("59.9"))
    }

    @Test
    fun successfulResponseMapsThroughExistingRepositoryResult() = runBlocking {
        val transport = RecordingTransport(successResponse())
        val result = source(transport).forecast(
            coordinate = ForecastCoordinate(59.9, 30.3),
            elevationMeters = 15,
            timeZoneId = "Europe/Moscow",
            generatedAt = Instant.parse("2026-10-01T10:00:00Z"),
        )

        val available = assertIs<ForecastRefreshSourceResult.Available>(result)
        assertEquals(1, available.sourceForecasts.size)
        assertEquals(1, available.failedSources.size)
        assertTrue(available.degraded)
    }

    @Test
    fun unexpectedStatusTypeMalformedOrCrossTargetResponseIsUnavailable() = runBlocking {
        val cases = listOf(
            BoundedHttpsResult.Success(
                BoundedHttpsResponse(
                    statusCode = 503,
                    headers = mapOf("Content-Type" to listOf("application/json")),
                    body = """{"error":"forecast_unavailable"}""".encodeToByteArray(),
                ),
            ),
            BoundedHttpsResult.Success(
                BoundedHttpsResponse(
                    statusCode = 200,
                    headers = mapOf("Content-Type" to listOf("text/plain")),
                    body = "no".encodeToByteArray(),
                ),
            ),
            BoundedHttpsResult.Success(
                BoundedHttpsResponse(
                    statusCode = 200,
                    headers = mapOf("Content-Type" to listOf("application/json")),
                    body = "{".encodeToByteArray(),
                ),
            ),
            successResponse(latitude = 60.0),
            BoundedHttpsResult.Failure(BoundedHttpsFailureReason.IO),
            BoundedHttpsResult.Failure(BoundedHttpsFailureReason.RESPONSE_TOO_LARGE),
        )

        cases.forEach { backendResult ->
            val result = source(RecordingTransport(backendResult)).forecast(
                coordinate = ForecastCoordinate(59.9, 30.3),
                elevationMeters = 15,
                timeZoneId = "Europe/Moscow",
                generatedAt = Instant.parse("2026-10-01T10:00:00Z"),
            )
            assertEquals(ForecastRefreshSourceResult.Unavailable, result)
        }
    }

    @Test
    fun cancellationCancelsActiveBackendCall() = runBlocking {
        val transport = BlockingTransport()
        val source = source(transport)

        val execution = async {
            source.forecast(
                coordinate = ForecastCoordinate(59.9, 30.3),
                elevationMeters = 15,
                timeZoneId = "Europe/Moscow",
                generatedAt = Instant.parse("2026-10-01T10:00:00Z"),
            )
        }

        assertTrue(
            withContext(Dispatchers.IO) {
                transport.entered.await(5, TimeUnit.SECONDS)
            },
        )
        execution.cancel()
        execution.join()

        assertTrue(transport.cancelled.get())
    }

    private fun source(transport: BoundedHttpsTransport): BackendForecastRefreshSource =
        BackendForecastRefreshSource(
            endpoint = BackendForecastEndpoint(
                URI.create("https://api.example/v1/forecast"),
            ),
            transport = transport,
        )

    private fun successResponse(
        latitude: Double = 59.9,
    ): BoundedHttpsResult {
        val generatedAt = Instant.parse("2026-10-01T10:00:00Z")
        val location = ForecastLocation(
            latitude = latitude,
            longitude = 30.3,
            elevationMeters = 15,
            timeZoneId = "Europe/Moscow",
        )
        val point = HourlyWeatherPoint(
            time = Instant.parse("2026-10-01T11:00:00Z"),
            temperatureC = 10.0,
            feelsLikeC = 9.0,
            dewPointC = 6.0,
            humidityPercent = 70.0,
            pressureSeaLevelHpa = 1013.0,
            windSpeedMps = 3.0,
            windGustMps = null,
            windDirectionDegrees = 180.0,
            precipitationMm = 0.0,
            precipitationProbabilityPercent = 10.0,
            cloudCoverPercent = 40.0,
            visibilityMeters = 10_000.0,
            condition = WeatherCondition.CLOUDY,
            windGustInterval = null,
            precipitationInterval = null,
        )
        val source = SourceForecast(
            origin = ForecastOrigin(
                provider = ForecastProvider.OPEN_METEO,
                modelFamily = ModelFamily.NOAA_GFS,
                modelRun = Instant.parse("2026-10-01T06:00:00Z"),
                generatedAt = generatedAt,
            ),
            location = location,
            hourly = listOf(point),
        )
        val response = ForecastResponseDto.fromDomain(
            forecast = FusedForecast(
                location = location,
                generatedAt = generatedAt,
                hourly = listOf(
                    FusedHourlyForecast(
                        weather = point,
                        providerCount = 1,
                        independentEvidenceCount = 1,
                        agreement = ModelAgreement.LOW,
                    ),
                ),
            ),
            sourceForecasts = listOf(source),
            failedSources = listOf(
                ForecastSourceIdentityValue(
                    provider = ForecastProvider.ECMWF_OPEN_DATA,
                    modelFamily = ModelFamily.ECMWF_IFS,
                ),
            ),
        )
        return BoundedHttpsResult.Success(
            BoundedHttpsResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to listOf("application/json; charset=UTF-8")),
                body = ForecastWireJson.json.encodeToString(
                    ForecastResponseDto.serializer(),
                    response,
                ).encodeToByteArray(),
            ),
        )
    }

    private class RecordingTransport(
        private val result: BoundedHttpsResult,
    ) : BoundedHttpsTransport {
        val requests = mutableListOf<BoundedHttpsRequest>()

        override fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall {
            requests += request
            return object : BoundedHttpsCall {
                override fun execute(): BoundedHttpsResult = result
                override fun cancel() = Unit
            }
        }
    }

    private class BlockingTransport : BoundedHttpsTransport {
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        private val release = CountDownLatch(1)

        override fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall =
            object : BoundedHttpsCall {
                override fun execute(): BoundedHttpsResult {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return BoundedHttpsResult.Failure(BoundedHttpsFailureReason.CANCELLED)
                }

                override fun cancel() {
                    cancelled.set(true)
                    release.countDown()
                }
            }
    }
}
