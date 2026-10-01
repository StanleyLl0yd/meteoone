package com.sl.meteoone.forecast.data.backend

import com.sl.meteoone.backend.contract.FORECAST_API_PATH
import com.sl.meteoone.backend.contract.ForecastRequestDto
import com.sl.meteoone.backend.contract.ForecastResponseDto
import com.sl.meteoone.backend.contract.ForecastWireJson
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastTarget
import com.sl.meteoone.core.network.BoundedHttpsMethod
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import com.sl.meteoone.core.network.DefaultBoundedHttpsTransport
import com.sl.meteoone.forecast.data.execution.M1ForecastEngineResult
import com.sl.meteoone.forecast.data.execution.M1ForecastSourceIdentity
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

private const val MAX_BACKEND_RESPONSE_BYTES: Long = 2L * 1024L * 1024L

class BackendForecastEndpoint private constructor(
    val uri: URI,
) {
    companion object {
        fun parse(value: String): BackendForecastEndpoint {
            val uri = URI.create(value)
            require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true)) {
                "MeteoOne backend forecast endpoint must use HTTPS"
            }
            require(!uri.host.isNullOrBlank()) {
                "MeteoOne backend forecast endpoint host is required"
            }
            require(uri.userInfo == null && uri.fragment == null && uri.query == null) {
                "MeteoOne backend forecast endpoint must not contain user info, query, or fragment"
            }
            require(uri.port == -1 || uri.port == 443) {
                "MeteoOne backend forecast endpoint must use the default HTTPS port"
            }
            require(uri.path == FORECAST_API_PATH) {
                "MeteoOne backend forecast endpoint must use $FORECAST_API_PATH"
            }
            return BackendForecastEndpoint(uri)
        }
    }
}

interface BackendForecastClient {
    suspend fun forecast(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        timeZoneId: String,
    ): M1ForecastEngineResult

    companion object {
        fun production(endpoint: BackendForecastEndpoint): BackendForecastClient =
            DefaultBackendForecastClient(
                endpoint = endpoint,
                transport = DefaultBoundedHttpsTransport(),
            )
    }
}

internal class DefaultBackendForecastClient(
    private val endpoint: BackendForecastEndpoint,
    private val transport: BoundedHttpsTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackendForecastClient {
    override suspend fun forecast(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        timeZoneId: String,
    ): M1ForecastEngineResult {
        val target = ForecastTarget(
            coordinate = coordinate,
            elevationMeters = elevationMeters,
            timeZoneId = timeZoneId,
        )
        val requestBody = ForecastWireJson.json.encodeToString(
            ForecastRequestDto.serializer(),
            ForecastRequestDto.fromDomain(target),
        ).encodeToByteArray()

        val request = BoundedHttpsRequest(
            uri = endpoint.uri,
            maxResponseBytes = MAX_BACKEND_RESPONSE_BYTES,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            ),
            method = BoundedHttpsMethod.POST,
            body = requestBody,
        )

        val result = execute(request)
        if (result !is BoundedHttpsResult.Success) return unavailable()

        val response = result.response
        if (response.statusCode != 200) return unavailable()

        val contentTypes = response.headerValues("Content-Type")
        if (
            contentTypes.size != 1 ||
            !contentTypes.single().substringBefore(';').trim()
                .equals("application/json", ignoreCase = true)
        ) {
            return unavailable()
        }

        val decoded = try {
            ForecastWireJson.json.decodeFromString(
                ForecastResponseDto.serializer(),
                response.body.decodeToString(),
            ).toDomain()
        } catch (_: SerializationException) {
            return unavailable()
        } catch (_: IllegalArgumentException) {
            return unavailable()
        }

        if (
            decoded.forecast.location.latitude != coordinate.latitude ||
            decoded.forecast.location.longitude != coordinate.longitude ||
            decoded.forecast.location.elevationMeters != elevationMeters ||
            decoded.forecast.location.timeZoneId != timeZoneId
        ) {
            return unavailable()
        }

        val successfulSources = decoded.sourceForecasts.map { source ->
            M1ForecastSourceIdentity(
                provider = source.origin.provider,
                modelFamily = source.origin.modelFamily,
            )
        }
        val failedSources = decoded.failedSources.map { identity ->
            M1ForecastSourceIdentity(
                provider = identity.provider,
                modelFamily = identity.modelFamily,
            )
        }
        return M1ForecastEngineResult.Available(
            forecast = decoded.forecast,
            sourceForecasts = decoded.sourceForecasts,
            successfulSources = successfulSources,
            failedSources = failedSources,
        )
    }

    private suspend fun execute(request: BoundedHttpsRequest): BoundedHttpsResult =
        withContext(ioDispatcher) {
            suspendCancellableCoroutine { continuation ->
                val call = transport.newCall(request)
                continuation.invokeOnCancellation {
                    call.cancel()
                }
                val result = call.execute()
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }

    private fun unavailable(): M1ForecastEngineResult =
        M1ForecastEngineResult.Unavailable(
            successfulCrossChecks = emptyList(),
            failedSources = emptyList(),
        )
}
