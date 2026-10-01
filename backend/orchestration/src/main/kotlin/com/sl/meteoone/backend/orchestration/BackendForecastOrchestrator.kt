package com.sl.meteoone.backend.orchestration

import com.sl.meteoone.backend.gateway.ForecastGatewayCacheKey
import com.sl.meteoone.backend.gateway.SingleFlightForecastGatewayCache
import com.sl.meteoone.backend.provideradapter.DirectOfficialServerForecastAdapters
import com.sl.meteoone.backend.provideradapter.OpenMeteoServerForecastAdapter
import com.sl.meteoone.backend.provideradapter.ServerForecastAdapterFailureReason
import com.sl.meteoone.backend.provideradapter.ServerForecastAdapterResult
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ForecastTarget
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.forecast.domain.FORECAST_HORIZON_HOURS
import com.sl.meteoone.forecast.domain.FORECAST_HOURLY_CADENCE
import com.sl.meteoone.forecast.domain.ForecastOfficialRunPolicy
import com.sl.meteoone.forecast.domain.ForecastOrchestrationResult
import com.sl.meteoone.forecast.domain.ForecastSourceIdentity
import com.sl.meteoone.forecast.domain.ForecastSourceOrchestrator
import com.sl.meteoone.forecast.domain.ForecastSourceResult
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

private val DEFAULT_CACHE_TTL: Duration = Duration.ofMinutes(10)
private const val DEFAULT_CACHE_ENTRIES: Int = 128

internal interface BackendForecastSources {
    suspend fun openMeteo(
        modelFamily: ModelFamily,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): ServerForecastAdapterResult

    suspend fun noaa(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): ServerForecastAdapterResult

    suspend fun ecmwf(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): ServerForecastAdapterResult

    suspend fun dwd(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): ServerForecastAdapterResult
}

internal class ProductionBackendForecastSources(
    private val openMeteo: OpenMeteoServerForecastAdapter,
    private val directOfficial: DirectOfficialServerForecastAdapters,
) : BackendForecastSources {
    override suspend fun openMeteo(
        modelFamily: ModelFamily,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): ServerForecastAdapterResult =
        openMeteo.fetch(
            modelFamily = modelFamily,
            coordinate = coordinate,
            location = location,
            generatedAt = generatedAt,
        )

    override suspend fun noaa(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): ServerForecastAdapterResult =
        directOfficial.fetchNoaa(
            modelRun = modelRun,
            forecastHour = forecastHour,
            coordinate = coordinate,
            location = location,
            generatedAt = generatedAt,
        )

    override suspend fun ecmwf(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): ServerForecastAdapterResult =
        directOfficial.fetchEcmwf(
            modelRun = modelRun,
            forecastHour = forecastHour,
            coordinate = coordinate,
            location = location,
            generatedAt = generatedAt,
        )

    override suspend fun dwd(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): ServerForecastAdapterResult =
        directOfficial.fetchDwd(
            modelRun = modelRun,
            forecastHour = forecastHour,
            coordinate = coordinate,
            location = location,
            generatedAt = generatedAt,
        )
}

/**
 * Central M5 forecast execution boundary.
 *
 * It deliberately preserves the accepted M1 source order and 72-hour Open-Meteo baseline while
 * moving provider execution to server adapters. The existing fusion engine still collapses
 * alternate delivery paths by model family, and until #241 supplies server verification evidence
 * its default weight provider remains the deterministic equal-weight fallback.
 */
class BackendForecastOrchestrator internal constructor(
    private val sources: BackendForecastSources,
    private val cache: SingleFlightForecastGatewayCache<BackendForecastResult.Available>,
    private val sourceOrchestrator: ForecastSourceOrchestrator = ForecastSourceOrchestrator(),
) {
    suspend fun forecast(
        target: ForecastTarget,
        requestedAt: Instant = Instant.now(),
    ): BackendForecastResult {
        val key = ForecastGatewayCacheKey.from(
            target = target,
            requestedAt = requestedAt,
        )
        return try {
            cache.getOrLoad(key) {
                when (val result = forecastUncached(target, requestedAt)) {
                    is BackendForecastResult.Available -> result
                    is BackendForecastResult.Unavailable -> throw UnavailableLoad(result)
                }
            }
        } catch (unavailable: UnavailableLoad) {
            unavailable.result
        }
    }

    private suspend fun forecastUncached(
        target: ForecastTarget,
        generatedAt: Instant,
    ): BackendForecastResult {
        val coordinate = target.coordinate
        val location = ForecastLocation(
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            elevationMeters = target.elevationMeters,
            timeZoneId = target.timeZoneId,
        )
        val modelRun = ForecastOfficialRunPolicy.selectModelRun(generatedAt)
        val hourlyForecastHour = ForecastOfficialRunPolicy.hourlyForecastHour(
            modelRun = modelRun,
            generatedAt = generatedAt,
        )
        val ecmwfForecastHour = ForecastOfficialRunPolicy.ecmwfForecastHour(
            modelRun = modelRun,
            generatedAt = generatedAt,
        )

        val attempts = listOf(
            attempt(identity(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS)) {
                sources.noaa(
                    modelRun = modelRun,
                    forecastHour = hourlyForecastHour,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                )
            },
            attempt(identity(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS)) {
                sources.openMeteo(
                    modelFamily = ModelFamily.NOAA_GFS,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                )
            },
            attempt(identity(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS)) {
                sources.ecmwf(
                    modelRun = modelRun,
                    forecastHour = ecmwfForecastHour,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                )
            },
            attempt(identity(ForecastProvider.OPEN_METEO, ModelFamily.ECMWF_IFS)) {
                sources.openMeteo(
                    modelFamily = ModelFamily.ECMWF_IFS,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                )
            },
            attempt(identity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON)) {
                sources.dwd(
                    modelRun = modelRun,
                    forecastHour = hourlyForecastHour,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                )
            },
            attempt(identity(ForecastProvider.OPEN_METEO, ModelFamily.DWD_ICON)) {
                sources.openMeteo(
                    modelFamily = ModelFamily.DWD_ICON,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                )
            },
        ).map(::validateOpenMeteoBaselineCandidate)

        val baseline = attempts
            .filterIsInstance<SourceAttempt.Success>()
            .firstOrNull { success ->
                success.identity.provider == ForecastProvider.OPEN_METEO
            }
            ?.forecast

        if (baseline == null) {
            return BackendForecastResult.Unavailable(
                successfulCrossChecks = attempts
                    .filterIsInstance<SourceAttempt.Success>()
                    .map(SourceAttempt.Success::identity)
                    .filter { it.provider != ForecastProvider.OPEN_METEO },
                failedSources = attempts
                    .filterIsInstance<SourceAttempt.Failure>()
                    .map(SourceAttempt.Failure::toBackendFailure),
            )
        }

        val horizonTimes = baseline.hourly.map { point -> point.time }
        val horizon = horizonTimes.toHashSet()
        val horizonAttempts = attempts.map { attempt ->
            attempt.withinHorizon(horizon)
        }
        val domainResults = horizonAttempts.map(SourceAttempt::toDomain)
        val combined = sourceOrchestrator.combine(domainResults)
        require(combined is ForecastOrchestrationResult.Available) {
            "A validated backend 72-hour baseline must produce an available forecast"
        }
        require(combined.forecast.hourly.map { it.weather.time } == horizonTimes) {
            "Backend fused forecast must preserve the exact 72-hour baseline horizon"
        }

        return BackendForecastResult.Available(
            forecast = combined.forecast,
            sourceForecasts = horizonAttempts
                .filterIsInstance<SourceAttempt.Success>()
                .map(SourceAttempt.Success::forecast),
            failedSources = horizonAttempts
                .filterIsInstance<SourceAttempt.Failure>()
                .map(SourceAttempt.Failure::toBackendFailure),
        )
    }

    private suspend fun attempt(
        identity: ForecastSourceIdentity,
        block: suspend () -> ServerForecastAdapterResult,
    ): SourceAttempt =
        try {
            when (val result = block()) {
                is ServerForecastAdapterResult.Success -> {
                    val forecast = result.forecast
                    require(
                        forecast.origin.provider == identity.provider &&
                            forecast.origin.modelFamily == identity.modelFamily
                    ) {
                        "Backend source provenance does not match the attempted identity"
                    }
                    SourceAttempt.Success(identity, forecast)
                }

                is ServerForecastAdapterResult.Failure -> {
                    require(
                        result.provider == identity.provider &&
                            result.modelFamily == identity.modelFamily
                    ) {
                        "Backend source failure provenance does not match the attempted identity"
                    }
                    SourceAttempt.Failure(
                        identity = identity,
                        reason = result.reason.toBackendFailureReason(),
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IllegalArgumentException) {
            SourceAttempt.Failure(identity, BackendForecastFailureReason.INVALID_RESPONSE)
        } catch (_: IllegalStateException) {
            SourceAttempt.Failure(identity, BackendForecastFailureReason.INVALID_RESPONSE)
        } catch (_: LinkageError) {
            SourceAttempt.Failure(identity, BackendForecastFailureReason.INVALID_RESPONSE)
        }

    private fun validateOpenMeteoBaselineCandidate(attempt: SourceAttempt): SourceAttempt {
        if (
            attempt !is SourceAttempt.Success ||
            attempt.identity.provider != ForecastProvider.OPEN_METEO
        ) {
            return attempt
        }
        val hourly = attempt.forecast.hourly
        val valid = hourly.size == FORECAST_HORIZON_HOURS &&
            hourly.zipWithNext().all { (previous, next) ->
                Duration.between(previous.time, next.time) == FORECAST_HOURLY_CADENCE
            }
        return if (valid) {
            attempt
        } else {
            SourceAttempt.Failure(
                identity = attempt.identity,
                reason = BackendForecastFailureReason.INVALID_RESPONSE,
            )
        }
    }

    companion object {
        fun production(
            serverNativeBundleRoot: Path,
            clock: Clock = Clock.systemUTC(),
            cacheTtl: Duration = DEFAULT_CACHE_TTL,
            cacheEntries: Int = DEFAULT_CACHE_ENTRIES,
        ): BackendForecastOrchestrator =
            BackendForecastOrchestrator(
                sources = ProductionBackendForecastSources(
                    openMeteo = OpenMeteoServerForecastAdapter.production(),
                    directOfficial = DirectOfficialServerForecastAdapters.production(
                        serverNativeBundleRoot,
                    ),
                ),
                cache = SingleFlightForecastGatewayCache(
                    ttl = cacheTtl,
                    maxEntries = cacheEntries,
                    clock = clock,
                ),
            )
    }
}

private sealed interface SourceAttempt {
    val identity: ForecastSourceIdentity

    data class Success(
        override val identity: ForecastSourceIdentity,
        val forecast: SourceForecast,
    ) : SourceAttempt

    data class Failure(
        override val identity: ForecastSourceIdentity,
        val reason: BackendForecastFailureReason,
    ) : SourceAttempt {
        fun toBackendFailure(): BackendFailedSource =
            BackendFailedSource(
                identity = identity,
                reason = reason,
            )
    }

    fun toDomain(): ForecastSourceResult =
        when (this) {
            is Success -> ForecastSourceResult.Success(forecast)
            is Failure -> ForecastSourceResult.Failure(identity)
        }

    fun withinHorizon(horizon: Set<Instant>): SourceAttempt =
        when (this) {
            is Failure -> this
            is Success -> {
                val hourly = forecast.hourly.filter { point -> point.time in horizon }
                when {
                    hourly.isEmpty() ->
                        Failure(identity, BackendForecastFailureReason.INVALID_RESPONSE)

                    hourly.size == forecast.hourly.size -> this

                    else ->
                        Success(
                            identity = identity,
                            forecast = SourceForecast(
                                origin = forecast.origin,
                                location = forecast.location,
                                hourly = hourly,
                            ),
                        )
                }
            }
        }
}

private class UnavailableLoad(
    val result: BackendForecastResult.Unavailable,
) : RuntimeException()

private fun identity(
    provider: ForecastProvider,
    modelFamily: ModelFamily,
): ForecastSourceIdentity =
    ForecastSourceIdentity(
        provider = provider,
        modelFamily = modelFamily,
    )

private fun ServerForecastAdapterFailureReason.toBackendFailureReason(): BackendForecastFailureReason =
    when (this) {
        ServerForecastAdapterFailureReason.MISSING_CREDENTIAL ->
            BackendForecastFailureReason.MISSING_CREDENTIAL
        ServerForecastAdapterFailureReason.CIRCUIT_OPEN ->
            BackendForecastFailureReason.CIRCUIT_OPEN
        ServerForecastAdapterFailureReason.CANCELLED ->
            BackendForecastFailureReason.CANCELLED
        ServerForecastAdapterFailureReason.IO ->
            BackendForecastFailureReason.IO
        ServerForecastAdapterFailureReason.RESPONSE_TOO_LARGE ->
            BackendForecastFailureReason.RESPONSE_TOO_LARGE
        ServerForecastAdapterFailureReason.INVALID_RESPONSE ->
            BackendForecastFailureReason.INVALID_RESPONSE
    }
