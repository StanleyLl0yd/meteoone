package com.sl.meteoone.backend.provideradapter

import com.sl.meteoone.backend.provider.ProviderGateway
import com.sl.meteoone.backend.provider.ProviderGatewayFailureReason
import com.sl.meteoone.backend.provider.ProviderGatewayRequest
import com.sl.meteoone.backend.provider.ProviderGatewayResult
import com.sl.meteoone.backend.provider.ProviderResponseValidator
import com.sl.meteoone.backend.provider.ServerProviderGateway
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoForecastMapper
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoForecastRequestPlanner
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoModel
import java.time.Duration
import java.time.Instant

class OpenMeteoServerForecastAdapter internal constructor(
    private val gateway: ProviderGateway,
    private val mapper: OpenMeteoForecastMapper = OpenMeteoForecastMapper(),
) {
    suspend fun fetch(
        modelFamily: ModelFamily,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): ServerForecastAdapterResult {
        require(
            location.latitude == coordinate.latitude &&
                location.longitude == coordinate.longitude
        ) {
            "Server Open-Meteo location must match the privacy-normalized coordinate"
        }

        val model = modelFamily.toOpenMeteoModel()
        val request = OpenMeteoForecastRequestPlanner.plan(
            model = model,
            coordinate = coordinate,
            generatedAt = generatedAt,
        )
        val gatewayRequest = ProviderGatewayRequest(
            provider = request.provider,
            modelFamily = request.modelFamily,
            uri = request.uri,
            maxResponseBytes = request.maxResponseBytes,
            expectedStatusCodes = setOf(200),
            minimumRequestSpacing = Duration.ZERO,
        )

        var mappedForecast: SourceForecast? = null
        val validator = ProviderResponseValidator { response ->
            if (
                response.provider != request.provider ||
                response.modelFamily != request.modelFamily ||
                response.statusCode != 200
            ) {
                false
            } else {
                mappedForecast = try {
                    mapper.map(
                        request = request,
                        generatedAt = generatedAt,
                        location = location,
                        payload = response.body.toString(Charsets.UTF_8),
                    )
                } catch (_: IllegalArgumentException) {
                    null
                }
                mappedForecast != null
            }
        }

        return when (val result = gateway.execute(gatewayRequest, validator)) {
            is ProviderGatewayResult.Success -> {
                val forecast = checkNotNull(mappedForecast) {
                    "Successful provider validation must produce a canonical forecast"
                }
                require(
                    forecast.origin.provider == ForecastProvider.OPEN_METEO &&
                        forecast.origin.modelFamily == modelFamily
                ) {
                    "Server Open-Meteo mapper provenance must match the requested model family"
                }
                ServerForecastAdapterResult.Success(forecast)
            }

            is ProviderGatewayResult.Failure ->
                ServerForecastAdapterResult.Failure(
                    provider = result.provider,
                    modelFamily = result.modelFamily,
                    reason = result.reason.toAdapterFailure(),
                )
        }
    }

    companion object {
        fun production(): OpenMeteoServerForecastAdapter =
            OpenMeteoServerForecastAdapter(
                gateway = ServerProviderGateway.production(),
            )
    }
}

private fun ModelFamily.toOpenMeteoModel(): OpenMeteoModel =
    when (this) {
        ModelFamily.ECMWF_IFS -> OpenMeteoModel.ECMWF_IFS
        ModelFamily.DWD_ICON -> OpenMeteoModel.DWD_ICON_GLOBAL
        ModelFamily.NOAA_GFS -> OpenMeteoModel.NOAA_GFS_GLOBAL
        ModelFamily.UNKNOWN -> throw IllegalArgumentException(
            "Open-Meteo backend adapter requires a supported model family",
        )
    }

private fun ProviderGatewayFailureReason.toAdapterFailure(): ServerForecastAdapterFailureReason =
    when (this) {
        ProviderGatewayFailureReason.MISSING_CREDENTIAL ->
            ServerForecastAdapterFailureReason.MISSING_CREDENTIAL
        ProviderGatewayFailureReason.CIRCUIT_OPEN ->
            ServerForecastAdapterFailureReason.CIRCUIT_OPEN
        ProviderGatewayFailureReason.CANCELLED ->
            ServerForecastAdapterFailureReason.CANCELLED
        ProviderGatewayFailureReason.IO ->
            ServerForecastAdapterFailureReason.IO
        ProviderGatewayFailureReason.RESPONSE_TOO_LARGE ->
            ServerForecastAdapterFailureReason.RESPONSE_TOO_LARGE
        ProviderGatewayFailureReason.INVALID_RESPONSE ->
            ServerForecastAdapterFailureReason.INVALID_RESPONSE
    }
