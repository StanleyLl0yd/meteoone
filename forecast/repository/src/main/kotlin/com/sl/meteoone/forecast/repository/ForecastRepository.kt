package com.sl.meteoone.forecast.repository

import android.content.Context
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
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
            AndroidForecastRepositoryHolder.get(context.applicationContext)
    }
}

private object AndroidForecastRepositoryHolder {
    @Volatile
    private var instance: ForecastRepository? = null

    fun get(context: Context): ForecastRepository =
        instance ?: synchronized(this) {
            instance ?: createAndroidForecastRepository(context)
                .also { repository -> instance = repository }
        }
}

data class ForecastSourceIdentity(
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
)

data class ForecastCacheState(
    val forecast: FusedForecast,
    val freshness: ForecastFreshness,
    val sourceForecasts: List<SourceForecast> = emptyList(),
    val failedSources: List<ForecastSourceIdentity> = emptyList(),
) {
    init {
        val successful = sourceForecasts.map { source ->
            ForecastSourceIdentity(source.origin.provider, source.origin.modelFamily)
        }
        require(successful.size == successful.toSet().size) {
            "Cached successful forecast source identities must be unique"
        }
        require(failedSources.size == failedSources.toSet().size) {
            "Cached failed forecast source identities must be unique"
        }
        require(successful.toSet().intersect(failedSources.toSet()).isEmpty()) {
            "Cached forecast source identity cannot be both successful and failed"
        }
    }

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
