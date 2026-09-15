package com.sl.meteoone.forecast.repository

import android.content.Context
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.FusedForecast
import kotlinx.coroutines.flow.Flow

interface ForecastRepository {
    fun observe(coordinate: ForecastCoordinate): Flow<ForecastCacheState?>

    suspend fun refresh(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        timeZoneId: String,
    ): ForecastRefreshResult

    companion object {
        fun android(context: Context): ForecastRepository =
            createAndroidForecastRepository(context.applicationContext)
    }
}

data class ForecastCacheState(
    val forecast: FusedForecast,
    val freshness: ForecastFreshness,
) {
    val shouldRefresh: Boolean
        get() = freshness != ForecastFreshness.FRESH
}

enum class ForecastFreshness {
    FRESH,
    STALE,
    EXPIRED,
}

sealed interface ForecastRefreshResult {
    data object Updated : ForecastRefreshResult

    data object UpdatedWithDegradation : ForecastRefreshResult

    data object Unavailable : ForecastRefreshResult

    data object Failed : ForecastRefreshResult
}
