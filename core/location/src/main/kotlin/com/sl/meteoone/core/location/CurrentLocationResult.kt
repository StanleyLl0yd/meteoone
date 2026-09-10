package com.sl.meteoone.core.location

sealed interface CurrentLocationResult {
    data class Available(
        val coordinate: ForecastCoordinate,
    ) : CurrentLocationResult

    data class Unavailable(
        val reason: Reason,
    ) : CurrentLocationResult

    enum class Reason {
        PERMISSION_REQUIRED,
        PROVIDER_UNAVAILABLE,
        TIMEOUT,
        CANCELLED,
        PLATFORM_FAILURE,
    }
}

fun interface LocationRequestHandle {
    fun cancel()
}
