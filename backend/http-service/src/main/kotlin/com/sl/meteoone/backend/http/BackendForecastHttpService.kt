package com.sl.meteoone.backend.http

import com.sl.meteoone.backend.contract.FORECAST_API_PATH
import com.sl.meteoone.backend.contract.ForecastRequestDto
import com.sl.meteoone.backend.contract.ForecastResponseDto
import com.sl.meteoone.backend.contract.ForecastSourceIdentityValue
import com.sl.meteoone.backend.contract.ForecastWireJson
import com.sl.meteoone.backend.orchestration.BackendForecastResult
import com.sl.meteoone.core.model.ForecastTarget
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.handle
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

const val BACKEND_LIVENESS_PATH: String = "/health/live"
const val BACKEND_READINESS_PATH: String = "/health/ready"

private const val DEFAULT_MAX_REQUEST_BODY_BYTES: Long = 4 * 1024
private const val MAX_REQUEST_BODY_BYTES: Long = 64 * 1024
private const val DEFAULT_MAX_CONCURRENT_FORECAST_REQUESTS: Int = 32
private const val MAX_CONCURRENT_FORECAST_REQUESTS: Int = 256
private val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(120)
private val MAX_REQUEST_TIMEOUT: Duration = Duration.ofMinutes(5)

data class BackendHttpServiceConfig(
    val maxRequestBodyBytes: Long = DEFAULT_MAX_REQUEST_BODY_BYTES,
    val maxConcurrentForecastRequests: Int = DEFAULT_MAX_CONCURRENT_FORECAST_REQUESTS,
    val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
) {
    init {
        require(maxRequestBodyBytes in 1..MAX_REQUEST_BODY_BYTES) {
            "Backend request body limit must be between 1 and $MAX_REQUEST_BODY_BYTES bytes"
        }
        require(maxConcurrentForecastRequests in 1..MAX_CONCURRENT_FORECAST_REQUESTS) {
            "Backend forecast concurrency must be between 1 and $MAX_CONCURRENT_FORECAST_REQUESTS"
        }
        require(!requestTimeout.isZero && !requestTimeout.isNegative && requestTimeout <= MAX_REQUEST_TIMEOUT) {
            "Backend request timeout must be positive and at most $MAX_REQUEST_TIMEOUT"
        }
    }
}

fun interface BackendForecastRequestHandler {
    suspend fun forecast(target: ForecastTarget): BackendForecastResult
}

fun Application.installMeteoOneHttpService(
    handler: BackendForecastRequestHandler,
    config: BackendHttpServiceConfig = BackendHttpServiceConfig(),
) {
    val permits = Semaphore(config.maxConcurrentForecastRequests, true)

    install(HttpRequestLifecycle) {
        cancelCallOnClose = true
    }

    routing {
        get(BACKEND_LIVENESS_PATH) {
            call.respondJson(HttpStatusCode.OK, """{"status":"live"}""")
        }

        get(BACKEND_READINESS_PATH) {
            call.respondJson(HttpStatusCode.OK, """{"status":"ready"}""")
        }

        route(FORECAST_API_PATH) {
            install(RequestBodyLimit) {
                bodyLimit { config.maxRequestBodyBytes }
            }

            handle {
                if (call.request.httpMethod != HttpMethod.Post) {
                    call.response.header(HttpHeaders.Allow, HttpMethod.Post.value)
                    call.respondJson(
                        HttpStatusCode.MethodNotAllowed,
                        """{"error":"method_not_allowed"}""",
                    )
                    return@handle
                }

                val rawContentType = call.request.headers[HttpHeaders.ContentType]
                    ?.substringBefore(';')
                    ?.trim()
                if (!rawContentType.equals("application/json", ignoreCase = true)) {
                    call.respondJson(
                        HttpStatusCode.UnsupportedMediaType,
                        """{"error":"unsupported_media_type"}""",
                    )
                    return@handle
                }

                val contentLengthHeader = call.request.headers[HttpHeaders.ContentLength]
                if (contentLengthHeader != null) {
                    val contentLength = contentLengthHeader.toLongOrNull()
                    if (contentLength == null || contentLength < 0L) {
                        call.respondJson(
                            HttpStatusCode.BadRequest,
                            """{"error":"invalid_content_length"}""",
                        )
                        return@handle
                    }
                    if (contentLength > config.maxRequestBodyBytes) {
                        call.respondJson(
                            HttpStatusCode.PayloadTooLarge,
                            """{"error":"request_too_large"}""",
                        )
                        return@handle
                    }
                }

                val request = try {
                    val body = call.receiveText()
                    ForecastWireJson.json
                        .decodeFromString(ForecastRequestDto.serializer(), body)
                        .toDomain()
                } catch (_: SerializationException) {
                    call.respondJson(HttpStatusCode.BadRequest, """{"error":"invalid_request"}""")
                    return@handle
                } catch (_: IllegalArgumentException) {
                    call.respondJson(HttpStatusCode.BadRequest, """{"error":"invalid_request"}""")
                    return@handle
                }

                if (!permits.tryAcquire()) {
                    call.respondJson(
                        HttpStatusCode.ServiceUnavailable,
                        """{"error":"server_busy"}""",
                    )
                    return@handle
                }

                try {
                    val result = try {
                        withTimeoutOrNull(config.requestTimeout.toMillis()) {
                            handler.forecast(request)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    } catch (_: LinkageError) {
                        null
                    }

                    when (result) {
                        null -> call.respondJson(
                            HttpStatusCode.GatewayTimeout,
                            """{"error":"forecast_timeout"}""",
                        )

                        is BackendForecastResult.Available -> {
                            val response = ForecastResponseDto.fromDomain(
                                forecast = result.forecast,
                                sourceForecasts = result.sourceForecasts,
                                failedSources = result.failedSources.map { failed ->
                                    ForecastSourceIdentityValue(
                                        provider = failed.identity.provider,
                                        modelFamily = failed.identity.modelFamily,
                                    )
                                },
                            )
                            call.respondJson(
                                HttpStatusCode.OK,
                                ForecastWireJson.json.encodeToString(
                                    ForecastResponseDto.serializer(),
                                    response,
                                ),
                            )
                        }

                        is BackendForecastResult.Unavailable -> call.respondJson(
                            HttpStatusCode.ServiceUnavailable,
                            """{"error":"forecast_unavailable"}""",
                        )
                    }
                } finally {
                    permits.release()
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(
    status: HttpStatusCode,
    body: String,
) {
    respondText(
        text = body,
        contentType = ContentType.Application.Json,
        status = status,
    )
}
