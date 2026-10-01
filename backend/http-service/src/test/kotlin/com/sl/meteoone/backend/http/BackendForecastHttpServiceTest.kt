package com.sl.meteoone.backend.http

import com.sl.meteoone.backend.contract.FORECAST_API_PATH
import com.sl.meteoone.backend.contract.ForecastRequestDto
import com.sl.meteoone.backend.contract.ForecastResponseDto
import com.sl.meteoone.backend.contract.ForecastWireJson
import com.sl.meteoone.backend.orchestration.BackendForecastResult
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ForecastTarget
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.model.WeatherCondition
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString

class BackendForecastHttpServiceTest {
    @Test
    fun healthEndpointsAreMinimalAndForecastMethodIsStrict() = testApplication {
        application {
            installMeteoOneHttpService(
                BackendForecastRequestHandler { availableFixture() },
            )
        }

        assertEquals(HttpStatusCode.OK, client.get(BACKEND_LIVENESS_PATH).status)
        assertEquals(HttpStatusCode.OK, client.get(BACKEND_READINESS_PATH).status)

        val wrongMethod = client.get(FORECAST_API_PATH)
        assertEquals(HttpStatusCode.MethodNotAllowed, wrongMethod.status)
        assertEquals("POST", wrongMethod.headers[HttpHeaders.Allow])
    }

    @Test
    fun forecastRequiresJsonAndRejectsInvalidRequestBeforeHandler() = testApplication {
        val calls = AtomicInteger()
        application {
            installMeteoOneHttpService(
                BackendForecastRequestHandler {
                    calls.incrementAndGet()
                    availableFixture()
                },
            )
        }

        val wrongType = client.post(FORECAST_API_PATH) {
            setBody(validRequestJson())
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, wrongType.status)

        val invalid = client.post(FORECAST_API_PATH) {
            contentType(ContentType.Application.Json)
            setBody("""{"latitudeTenths":901,"longitudeTenths":0,"elevationMeters":null,"timeZoneId":"UTC"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals(0, calls.get())
    }

    @Test
    fun availableForecastUsesVersionedStrictContract() = testApplication {
        application {
            installMeteoOneHttpService(
                BackendForecastRequestHandler { availableFixture() },
            )
        }

        val response = client.post(FORECAST_API_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validRequestJson())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val decoded = ForecastWireJson.json.decodeFromString<ForecastResponseDto>(
            response.bodyAsText(),
        ).toDomain()
        assertEquals(1, decoded.forecast.hourly.size)
        assertEquals(ForecastProvider.OPEN_METEO, decoded.sourceForecasts.single().origin.provider)
    }

    @Test
    fun unavailableForecastExposesNoProviderInternals() = testApplication {
        application {
            installMeteoOneHttpService(
                BackendForecastRequestHandler {
                    BackendForecastResult.Unavailable(
                        successfulCrossChecks = emptyList(),
                        failedSources = emptyList(),
                    )
                },
            )
        }

        val response = client.post(FORECAST_API_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validRequestJson())
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("""{"error":"forecast_unavailable"}""", response.bodyAsText())
        assertFalse(response.bodyAsText().contains("provider", ignoreCase = true))
    }

    @Test
    fun requestBodyLimitRejectsOversizeBeforeHandler() = testApplication {
        val calls = AtomicInteger()
        application {
            installMeteoOneHttpService(
                handler = BackendForecastRequestHandler {
                    calls.incrementAndGet()
                    availableFixture()
                },
                config = BackendHttpServiceConfig(maxRequestBodyBytes = 64),
            )
        }

        val response = client.post(FORECAST_API_PATH) {
            contentType(ContentType.Application.Json)
            setBody("{" + "\"padding\":\"" + "x".repeat(256) + "\"}")
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(0, calls.get())
    }

    @Test
    fun forecastExecutionHasBoundedTimeout() = testApplication {
        application {
            installMeteoOneHttpService(
                handler = BackendForecastRequestHandler {
                    delay(200)
                    availableFixture()
                },
                config = BackendHttpServiceConfig(
                    requestTimeout = Duration.ofMillis(20),
                ),
            )
        }

        val response = client.post(FORECAST_API_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validRequestJson())
        }

        assertEquals(HttpStatusCode.GatewayTimeout, response.status)
        assertEquals("""{"error":"forecast_timeout"}""", response.bodyAsText())
    }

    @Test
    fun concurrencyLimitFailsFastWithoutStartingExtraForecastWork() = testApplication {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()

        application {
            installMeteoOneHttpService(
                handler = BackendForecastRequestHandler {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    availableFixture()
                },
                config = BackendHttpServiceConfig(
                    maxConcurrentForecastRequests = 1,
                    requestTimeout = Duration.ofSeconds(5),
                ),
            )
        }

        coroutineScope {
            val first = async {
                client.post(FORECAST_API_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(validRequestJson())
                }
            }
            started.await()

            val second = client.post(FORECAST_API_PATH) {
                contentType(ContentType.Application.Json)
                setBody(validRequestJson())
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, second.status)
            assertEquals("""{"error":"server_busy"}""", second.bodyAsText())
            assertEquals(1, calls.get())

            release.complete(Unit)
            assertEquals(HttpStatusCode.OK, first.await().status)
        }
    }

    @Test
    fun internalHandlerFailureUsesFixedPublicError() = testApplication {
        application {
            installMeteoOneHttpService(
                BackendForecastRequestHandler {
                    error("provider-secret-must-not-leak")
                },
            )
        }

        val response = client.post(FORECAST_API_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validRequestJson())
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("""{"error":"forecast_failed"}""", response.bodyAsText())
        assertFalse(response.bodyAsText().contains("provider-secret"))
    }

    @Test
    fun runtimeConfigurationDefaultsToLoopbackAndRequiresNativeBundle() {
        val config = MeteoOneServerRuntimeConfig.fromEnvironment(
            mapOf("METEOONE_SERVER_NATIVE_BUNDLE" to "/srv/meteoone/native"),
        )

        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertTrue(config.nativeBundleRoot.isAbsolute)

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            MeteoOneServerRuntimeConfig.fromEnvironment(
                mapOf(
                    "METEOONE_SERVER_NATIVE_BUNDLE" to "/srv/meteoone/native",
                    "METEOONE_SERVER_PORT" to "not-a-port",
                ),
            )
        }
        kotlin.test.assertFailsWith<IllegalStateException> {
            MeteoOneServerRuntimeConfig.fromEnvironment(emptyMap())
        }
    }

    private fun validRequestJson(): String =
        ForecastWireJson.json.encodeToString(
            ForecastRequestDto.serializer(),
            ForecastRequestDto.fromDomain(
                ForecastTarget(
                    coordinate = ForecastCoordinate(59.9, 30.3),
                    elevationMeters = 15,
                    timeZoneId = "Europe/Moscow",
                ),
            ),
        )

    private fun availableFixture(): BackendForecastResult.Available {
        val generatedAt = Instant.parse("2026-10-01T10:00:00Z")
        val location = ForecastLocation(
            latitude = 59.9,
            longitude = 30.3,
            elevationMeters = 15,
            timeZoneId = "Europe/Moscow",
        )
        val weather = HourlyWeatherPoint(
            time = Instant.parse("2026-10-01T11:00:00Z"),
            temperatureC = 12.0,
            feelsLikeC = 11.0,
            dewPointC = 8.0,
            humidityPercent = 75.0,
            pressureSeaLevelHpa = 1012.0,
            windSpeedMps = 4.0,
            windGustMps = 6.0,
            windDirectionDegrees = 220.0,
            precipitationMm = 0.2,
            precipitationProbabilityPercent = 30.0,
            cloudCoverPercent = 60.0,
            visibilityMeters = 10_000.0,
            condition = WeatherCondition.RAIN,
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
            hourly = listOf(weather),
        )
        return BackendForecastResult.Available(
            forecast = FusedForecast(
                location = location,
                generatedAt = generatedAt,
                hourly = listOf(
                    FusedHourlyForecast(
                        weather = weather,
                        providerCount = 1,
                        independentEvidenceCount = 1,
                        agreement = ModelAgreement.LOW,
                    ),
                ),
            ),
            sourceForecasts = listOf(source),
            failedSources = emptyList(),
        )
    }
}
