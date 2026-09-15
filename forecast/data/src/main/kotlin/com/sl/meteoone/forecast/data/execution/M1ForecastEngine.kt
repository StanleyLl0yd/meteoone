package com.sl.meteoone.forecast.data.execution

import android.content.Context
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import com.sl.meteoone.core.network.DefaultBoundedHttpsTransport
import com.sl.meteoone.forecast.data.dwd.BoundedDwdBzip2Decompressor
import com.sl.meteoone.forecast.data.dwd.DwdIconField
import com.sl.meteoone.forecast.data.dwd.DwdIconGridGeometryPlanner
import com.sl.meteoone.forecast.data.dwd.DwdIconRequestPlanner
import com.sl.meteoone.forecast.data.ecmwf.EcmwfIfsFieldSelector
import com.sl.meteoone.forecast.data.ecmwf.EcmwfIfsRequestPlanner
import com.sl.meteoone.forecast.data.ecmwf.EcmwfSurfaceField
import com.sl.meteoone.forecast.data.grib.AndroidEcCodesNativeSession
import com.sl.meteoone.forecast.data.grib.DecodedGribField
import com.sl.meteoone.forecast.data.grib.EcCodesGribFieldDecoder
import com.sl.meteoone.forecast.data.grib.GribDecodeRequest
import com.sl.meteoone.forecast.data.grib.GribFieldDecoder
import com.sl.meteoone.forecast.data.grib.OfficialGribForecastMapper
import com.sl.meteoone.forecast.data.grib.RunScopedDwdIconGridGeometryProvider
import com.sl.meteoone.forecast.data.noaa.NoaaGfsRequestPlanner
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoForecastMapper
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoForecastRequestPlanner
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoModel
import com.sl.meteoone.forecast.data.transport.ForecastHttpAdapter
import com.sl.meteoone.forecast.domain.ForecastOrchestrationResult
import com.sl.meteoone.forecast.domain.ForecastSourceIdentity
import com.sl.meteoone.forecast.domain.ForecastSourceOrchestrator
import com.sl.meteoone.forecast.domain.ForecastSourceResult
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private const val M1_HORIZON_HOURS = 72
private const val SECONDS_PER_HOUR = 3600L
private val OFFICIAL_PUBLICATION_GUARD = Duration.ofHours(7)

internal interface ForecastSourceExecutor {
    fun openMeteo(
        model: OpenMeteoModel,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): SourceForecast

    fun noaa(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): SourceForecast

    fun ecmwf(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): SourceForecast

    fun dwd(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): SourceForecast
}

internal class ProductionForecastSourceExecutor(
    private val httpAdapter: ForecastHttpAdapter,
    private val gribDecoder: GribFieldDecoder,
    private val dwdDecompress: (ByteArray) -> ByteArray,
    private val openMeteoMapper: OpenMeteoForecastMapper = OpenMeteoForecastMapper(),
    private val officialMapper: OfficialGribForecastMapper = OfficialGribForecastMapper(),
) : ForecastSourceExecutor {
    override fun openMeteo(
        model: OpenMeteoModel,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): SourceForecast {
        val request = OpenMeteoForecastRequestPlanner.plan(model, coordinate)
        val payload = httpAdapter.newOrdinaryCall(request)
            .requireBody("Open-Meteo ${model.apiId}")
            .toString(Charsets.UTF_8)
        return openMeteoMapper.map(
            request = request,
            generatedAt = generatedAt,
            location = location,
            payload = payload,
        )
    }

    override fun noaa(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): SourceForecast {
        val plan = NoaaGfsRequestPlanner.plan(modelRun, coordinate, forecastHour)
        val payload = httpAdapter.newOrdinaryCall(plan.request).requireBody("NOAA GFS")
        return officialMapper.map(
            provider = plan.provider,
            modelRun = plan.modelRun,
            generatedAt = generatedAt,
            location = location,
            fields = gribDecoder.decode(GribDecodeRequest.noaa(payload, plan)),
        )
    }

    override fun ecmwf(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): SourceForecast {
        val plan = EcmwfIfsRequestPlanner.plan(modelRun, forecastHour)
        val index = httpAdapter.newOrdinaryCall(plan.indexRequest)
            .requireBody("ECMWF IFS index")
            .toString(Charsets.UTF_8)
        val rangePlan = EcmwfIfsFieldSelector.select(
            indexContent = index,
            plan = plan,
            fields = setOf(EcmwfSurfaceField.TEMPERATURE_2M),
        ).single()
        val payload = httpAdapter.newFieldRangeCall(rangePlan).requireBody("ECMWF IFS field")
        return officialMapper.map(
            provider = plan.provider,
            modelRun = plan.modelRun,
            generatedAt = generatedAt,
            location = location,
            fields = gribDecoder.decode(
                GribDecodeRequest.ecmwf(
                    payload = payload,
                    plan = rangePlan,
                    coordinate = coordinate,
                ),
            ),
        )
    }

    override fun dwd(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): SourceForecast {
        val plan = DwdIconRequestPlanner.plan(
            modelRun = modelRun,
            forecastHour = forecastHour,
            field = DwdIconField.TEMPERATURE_2M,
        )
        val compressed = httpAdapter.newOrdinaryCall(plan.request).requireBody("DWD ICON field")
        val payload = dwdDecompress(compressed)
        return officialMapper.map(
            provider = plan.provider,
            modelRun = plan.modelRun,
            generatedAt = generatedAt,
            location = location,
            fields = gribDecoder.decode(
                GribDecodeRequest.dwd(
                    payload = payload,
                    plan = plan,
                    coordinate = coordinate,
                    geometryPlan = DwdIconGridGeometryPlanner.plan(modelRun),
                ),
            ),
        )
    }
}

private fun BoundedHttpsCall.requireBody(label: String): ByteArray =
    when (val result = execute()) {
        is BoundedHttpsResult.Success -> result.response.body
        is BoundedHttpsResult.Failure -> throw IllegalStateException(
            "$label transport failed: ${result.reason}",
        )
    }

internal fun productionForecastSourceExecutor(
    context: Context,
    transport: BoundedHttpsTransport,
): ForecastSourceExecutor {
    val httpAdapter = ForecastHttpAdapter(transport)
    val decompressor = BoundedDwdBzip2Decompressor()
    val nativeSession = AndroidEcCodesNativeSession(context.applicationContext)
    val geometryProvider = RunScopedDwdIconGridGeometryProvider(
        httpAdapter = httpAdapter,
        decompressor = decompressor,
        nativeSession = nativeSession,
    )
    return ProductionForecastSourceExecutor(
        httpAdapter = httpAdapter,
        gribDecoder = EcCodesGribFieldDecoder(
            nativeSession = nativeSession,
            dwdGeometryProvider = geometryProvider,
        ),
        dwdDecompress = decompressor::decompress,
    )
}

sealed interface M1ForecastEngineResult {
    data class Available(
        val orchestration: ForecastOrchestrationResult.Available,
    ) : M1ForecastEngineResult

    data class Unavailable(
        val successfulCrossChecks: List<ForecastSourceIdentity>,
        val failedSources: List<ForecastSourceIdentity>,
    ) : M1ForecastEngineResult
}

class M1ForecastEngine internal constructor(
    private val sourceExecutor: ForecastSourceExecutor,
    private val orchestrator: ForecastSourceOrchestrator = ForecastSourceOrchestrator(),
) {
    /**
     * Executes bounded network work synchronously. Android callers must invoke this off the main thread.
     */
    fun forecast(
        location: ForecastLocation,
        generatedAt: Instant = Instant.now(),
    ): M1ForecastEngineResult {
        val coordinate = ForecastCoordinate(
            latitude = location.latitude,
            longitude = location.longitude,
        )
        val modelRun = M1OfficialRunPolicy.selectModelRun(generatedAt)
        val hourlyForecastHour = M1OfficialRunPolicy.hourlyForecastHour(modelRun, generatedAt)
        val ecmwfForecastHour = M1OfficialRunPolicy.ecmwfForecastHour(modelRun, generatedAt)

        val results = listOf(
            attempt(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS) {
                sourceExecutor.noaa(
                    modelRun = modelRun,
                    forecastHour = hourlyForecastHour,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                )
            },
            attempt(ForecastProvider.OPEN_METEO, ModelFamily.NOAA_GFS) {
                sourceExecutor.openMeteo(
                    model = OpenMeteoModel.NOAA_GFS_GLOBAL,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                )
            },
            attempt(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS) {
                sourceExecutor.ecmwf(
                    modelRun = modelRun,
                    forecastHour = ecmwfForecastHour,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                )
            },
            attempt(ForecastProvider.OPEN_METEO, ModelFamily.ECMWF_IFS) {
                sourceExecutor.openMeteo(
                    model = OpenMeteoModel.ECMWF_IFS,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                )
            },
            attempt(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON) {
                sourceExecutor.dwd(
                    modelRun = modelRun,
                    forecastHour = hourlyForecastHour,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                )
            },
            attempt(ForecastProvider.OPEN_METEO, ModelFamily.DWD_ICON) {
                sourceExecutor.openMeteo(
                    model = OpenMeteoModel.DWD_ICON_GLOBAL,
                    coordinate = coordinate,
                    location = location,
                    generatedAt = generatedAt,
                )
            },
        )

        val baseline = results
            .filterIsInstance<ForecastSourceResult.Success>()
            .firstOrNull { result -> result.forecast.origin.provider == ForecastProvider.OPEN_METEO }
            ?.forecast

        if (baseline == null) {
            return M1ForecastEngineResult.Unavailable(
                successfulCrossChecks = results
                    .filterIsInstance<ForecastSourceResult.Success>()
                    .map { it.identity }
                    .filter { it.provider != ForecastProvider.OPEN_METEO },
                failedSources = results
                    .filterIsInstance<ForecastSourceResult.Failure>()
                    .map { it.identity },
            )
        }

        val horizonTimes = baseline.hourly.map { it.time }
        require(horizonTimes.size == M1_HORIZON_HOURS) {
            "M1 baseline must contain exactly $M1_HORIZON_HOURS hourly points"
        }
        require(horizonTimes.zipWithNext().all { (previous, next) ->
            Duration.between(previous, next).seconds == SECONDS_PER_HOUR
        }) {
            "M1 baseline must use an exact hourly cadence"
        }
        val horizon = horizonTimes.toHashSet()
        val horizonResults = results.map { result -> result.withinHorizon(horizon) }

        val combined = orchestrator.combine(horizonResults)
        require(combined is ForecastOrchestrationResult.Available) {
            "A validated M1 72-hour baseline must produce an available forecast"
        }
        require(combined.forecast.hourly.map { it.weather.time } == horizonTimes) {
            "M1 fused forecast must preserve the exact 72-hour baseline horizon"
        }
        return M1ForecastEngineResult.Available(combined)
    }

    private fun attempt(
        provider: ForecastProvider,
        modelFamily: ModelFamily,
        block: () -> SourceForecast,
    ): ForecastSourceResult {
        val identity = ForecastSourceIdentity(provider, modelFamily)
        return try {
            val forecast = block()
            require(forecast.origin.provider == provider && forecast.origin.modelFamily == modelFamily) {
                "Forecast source provenance does not match the attempted source identity"
            }
            ForecastSourceResult.Success(forecast)
        } catch (_: Exception) {
            ForecastSourceResult.Failure(identity)
        } catch (_: LinkageError) {
            ForecastSourceResult.Failure(identity)
        }
    }

    private fun ForecastSourceResult.withinHorizon(
        horizon: Set<Instant>,
    ): ForecastSourceResult = when (this) {
        is ForecastSourceResult.Failure -> this
        is ForecastSourceResult.Success -> {
            val hourly = forecast.hourly.filter { it.time in horizon }
            if (hourly.isEmpty()) {
                ForecastSourceResult.Failure(identity)
            } else if (hourly.size == forecast.hourly.size) {
                this
            } else {
                ForecastSourceResult.Success(
                    SourceForecast(
                        origin = forecast.origin,
                        location = forecast.location,
                        hourly = hourly,
                    ),
                )
            }
        }
    }

    companion object {
        fun android(
            context: Context,
            transport: BoundedHttpsTransport = DefaultBoundedHttpsTransport(),
        ): M1ForecastEngine = M1ForecastEngine(
            sourceExecutor = productionForecastSourceExecutor(
                context = context,
                transport = transport,
            ),
        )
    }
}

internal object M1OfficialRunPolicy {
    fun selectModelRun(generatedAt: Instant): Instant {
        val eligible = generatedAt.minus(OFFICIAL_PUBLICATION_GUARD).atOffset(ZoneOffset.UTC)
        val cycleHour = eligible.hour / 6 * 6
        return eligible
            .toLocalDate()
            .atStartOfDay()
            .plusHours(cycleHour.toLong())
            .toInstant(ZoneOffset.UTC)
    }

    fun hourlyForecastHour(
        modelRun: Instant,
        generatedAt: Instant,
    ): Int = ceilForecastHour(modelRun, generatedAt)

    fun ecmwfForecastHour(
        modelRun: Instant,
        generatedAt: Instant,
    ): Int {
        val hourly = ceilForecastHour(modelRun, generatedAt)
        return ((hourly + 2) / 3) * 3
    }

    private fun ceilForecastHour(
        modelRun: Instant,
        generatedAt: Instant,
    ): Int {
        require(!generatedAt.isBefore(modelRun)) {
            "Forecast generation time must not precede the selected model run"
        }
        val elapsed = Duration.between(modelRun, generatedAt)
        val completedHours = elapsed.toHours()
        val hour = if (elapsed.minusHours(completedHours).isZero) {
            completedHours
        } else {
            completedHours + 1
        }
        require(hour in 0..M1_HORIZON_HOURS) {
            "Selected direct-source cross-check hour must remain inside the M1 horizon"
        }
        return hour.toInt()
    }
}
