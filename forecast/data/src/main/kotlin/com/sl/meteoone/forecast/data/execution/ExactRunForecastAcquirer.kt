package com.sl.meteoone.forecast.data.execution

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import com.sl.meteoone.core.network.DefaultBoundedHttpsTransport
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoModel
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoSingleRunMapper
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoSingleRunRequestPlanner
import com.sl.meteoone.forecast.data.transport.ForecastHttpAdapter
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private const val MAX_EXACT_RUNS_PER_ACQUISITION = 2
private val EXACT_RUN_PUBLICATION_GUARD: Duration = Duration.ofHours(7)
private val EXACT_RUN_MAX_AGE: Duration = Duration.ofDays(180)

data class ExactRunSourceIdentity(
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val modelRun: Instant,
)

data class ExactRunAcquisitionResult(
    val forecasts: List<SourceForecast>,
    val failedSources: List<ExactRunSourceIdentity>,
) {
    init {
        require(forecasts.size + failedSources.size <= MAX_EXACT_RUNS_PER_ACQUISITION * 3) {
            "Exact-run acquisition result exceeds the bounded request budget"
        }
        val successful = forecasts.map { forecast ->
            ExactRunSourceIdentity(
                provider = forecast.origin.provider,
                modelFamily = forecast.origin.modelFamily,
                modelRun = requireNotNull(forecast.origin.modelRun),
            )
        }
        require(successful.size == successful.toSet().size) {
            "Exact-run successful source identities must be unique"
        }
        require(failedSources.size == failedSources.toSet().size) {
            "Exact-run failed source identities must be unique"
        }
        require(successful.toSet().intersect(failedSources.toSet()).isEmpty()) {
            "Exact-run source identity cannot be both successful and failed"
        }
    }
}

class ExactRunForecastAcquirer internal constructor(
    private val source: ExactRunSource,
) {
    fun acquire(
        coordinate: ForecastCoordinate,
        elevationMeters: Int?,
        timeZoneId: String,
        modelRuns: List<Instant>,
        capturedAt: Instant = Instant.now(),
    ): ExactRunAcquisitionResult {
        require(modelRuns.isNotEmpty()) {
            "Exact-run acquisition requires at least one model run"
        }
        require(modelRuns.size <= MAX_EXACT_RUNS_PER_ACQUISITION) {
            "Exact-run acquisition is limited to $MAX_EXACT_RUNS_PER_ACQUISITION model runs"
        }
        require(modelRuns.size == modelRuns.toSet().size) {
            "Exact-run acquisition model runs must be unique"
        }
        val sortedRuns = modelRuns.sorted()
        require(sortedRuns == modelRuns) {
            "Exact-run acquisition model runs must be chronological"
        }
        val oldestAllowedRun = capturedAt.minus(EXACT_RUN_MAX_AGE)
        sortedRuns.forEach { run ->
            requireCommonDailyRun(run)
            require(!capturedAt.isBefore(run.plus(EXACT_RUN_PUBLICATION_GUARD))) {
                "Exact-run acquisition must wait for the common publication guard"
            }
            require(!run.isBefore(oldestAllowedRun)) {
                "Exact-run acquisition must remain inside the 180-day verification retention window"
            }
        }

        val location = ForecastLocation(
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            elevationMeters = elevationMeters,
            timeZoneId = timeZoneId,
        )
        val forecasts = mutableListOf<SourceForecast>()
        val failures = mutableListOf<ExactRunSourceIdentity>()
        sortedRuns.forEach { run ->
            MODELS.forEach { model ->
                val identity = ExactRunSourceIdentity(
                    provider = ForecastProvider.OPEN_METEO,
                    modelFamily = model.modelFamily,
                    modelRun = run,
                )
                try {
                    val forecast = source.fetch(
                        model = model,
                        coordinate = coordinate,
                        location = location,
                        modelRun = run,
                        capturedAt = capturedAt,
                    )
                    require(forecast.origin.provider == identity.provider) {
                        "Exact-run provider provenance does not match the requested source"
                    }
                    require(forecast.origin.modelFamily == identity.modelFamily) {
                        "Exact-run model-family provenance does not match the requested source"
                    }
                    require(forecast.origin.modelRun == identity.modelRun) {
                        "Exact-run initialization provenance does not match the requested source"
                    }
                    forecasts += forecast
                } catch (_: Exception) {
                    failures += identity
                } catch (_: LinkageError) {
                    failures += identity
                }
            }
        }
        return ExactRunAcquisitionResult(
            forecasts = forecasts,
            failedSources = failures,
        )
    }

    companion object {
        fun default(): ExactRunForecastAcquirer =
            ExactRunForecastAcquirer(
                source = HttpExactRunSource(
                    transport = DefaultBoundedHttpsTransport(),
                ),
            )
    }
}

internal interface ExactRunSource {
    fun fetch(
        model: OpenMeteoModel,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        modelRun: Instant,
        capturedAt: Instant,
    ): SourceForecast
}

internal class HttpExactRunSource(
    transport: BoundedHttpsTransport,
    private val mapper: OpenMeteoSingleRunMapper = OpenMeteoSingleRunMapper(),
) : ExactRunSource {
    private val httpAdapter = ForecastHttpAdapter(transport)

    override fun fetch(
        model: OpenMeteoModel,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        modelRun: Instant,
        capturedAt: Instant,
    ): SourceForecast {
        val request = OpenMeteoSingleRunRequestPlanner.plan(
            model = model,
            coordinate = coordinate,
            modelRun = modelRun,
        )
        val payload = when (val result = httpAdapter.newOrdinaryCall(request).execute()) {
            is BoundedHttpsResult.Success -> result.response.body
            is BoundedHttpsResult.Failure -> throw IllegalStateException(
                "Open-Meteo Single Runs transport failed: ${result.reason}",
            )
        }
        return mapper.map(
            request = request,
            capturedAt = capturedAt,
            location = location,
            payload = payload.toString(Charsets.UTF_8),
        )
    }
}

private fun requireCommonDailyRun(run: Instant) {
    val utc = run.atOffset(ZoneOffset.UTC)
    require(
        utc.hour == 0 &&
            utc.minute == 0 &&
            utc.second == 0 &&
            run.nano == 0
    ) {
        "Exact-run acquisition currently accepts only common 00Z daily runs"
    }
}

private val MODELS = listOf(
    OpenMeteoModel.ECMWF_IFS,
    OpenMeteoModel.DWD_ICON_GLOBAL,
    OpenMeteoModel.NOAA_GFS_GLOBAL,
)
