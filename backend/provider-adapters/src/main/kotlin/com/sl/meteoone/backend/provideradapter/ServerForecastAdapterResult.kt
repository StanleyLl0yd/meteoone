package com.sl.meteoone.backend.provideradapter

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast

enum class ServerForecastAdapterFailureReason {
    MISSING_CREDENTIAL,
    CIRCUIT_OPEN,
    CANCELLED,
    IO,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
}

sealed interface ServerForecastAdapterResult {
    data class Success(
        val forecast: SourceForecast,
    ) : ServerForecastAdapterResult

    data class Failure(
        val provider: ForecastProvider,
        val modelFamily: ModelFamily,
        val reason: ServerForecastAdapterFailureReason,
    ) : ServerForecastAdapterResult
}
