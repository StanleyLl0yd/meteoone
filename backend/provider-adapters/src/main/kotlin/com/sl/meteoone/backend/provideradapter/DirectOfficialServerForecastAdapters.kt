package com.sl.meteoone.backend.provideradapter

import com.sl.meteoone.backend.provider.ProviderByteRange
import com.sl.meteoone.backend.provider.ProviderGateway
import com.sl.meteoone.backend.provider.ProviderGatewayFailureReason
import com.sl.meteoone.backend.provider.ProviderGatewayRequest
import com.sl.meteoone.backend.provider.ProviderGatewayResponse
import com.sl.meteoone.backend.provider.ProviderGatewayResult
import com.sl.meteoone.backend.provider.ProviderResponseValidator
import com.sl.meteoone.backend.provider.ServerProviderGateway
import com.sl.meteoone.backend.servernative.ServerEcCodesRuntime
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.forecast.data.dwd.BoundedDwdBzip2Decompressor
import com.sl.meteoone.forecast.data.dwd.DwdIconField
import com.sl.meteoone.forecast.data.dwd.DwdIconGridGeometryPlan
import com.sl.meteoone.forecast.data.dwd.DwdIconGridGeometryPlanner
import com.sl.meteoone.forecast.data.dwd.DwdIconRequestPlanner
import com.sl.meteoone.forecast.data.ecmwf.EcmwfFieldRangePlan
import com.sl.meteoone.forecast.data.ecmwf.EcmwfIfsFieldSelector
import com.sl.meteoone.forecast.data.ecmwf.EcmwfIfsRequestPlanner
import com.sl.meteoone.forecast.data.ecmwf.EcmwfSurfaceField
import com.sl.meteoone.forecast.data.grib.DwdIconGridGeometry
import com.sl.meteoone.forecast.data.grib.DwdIconGridGeometryDecoder
import com.sl.meteoone.forecast.data.grib.DwdIconGridGeometryProvider
import com.sl.meteoone.forecast.data.grib.EcCodesGribFieldDecoder
import com.sl.meteoone.forecast.data.grib.EcCodesNativeSession
import com.sl.meteoone.forecast.data.grib.GribDecodeRequest
import com.sl.meteoone.forecast.data.grib.OfficialGribForecastMapper
import com.sl.meteoone.forecast.data.noaa.NoaaGfsRequestPlanner
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Server execution of the already-accepted M1 direct-official source contracts.
 *
 * Each method intentionally returns one canonical direct-source forecast slice for one planned
 * model run/forecast hour. Fusion, fallback selection and 72-hour orchestration remain outside
 * this adapter until M5 #240.
 */
class DirectOfficialServerForecastAdapters internal constructor(
    private val gateway: ProviderGateway,
    private val nativeSession: EcCodesNativeSession,
    private val mapper: OfficialGribForecastMapper = OfficialGribForecastMapper(),
    private val dwdDecompressor: BoundedDwdBzip2Decompressor = BoundedDwdBzip2Decompressor(),
    private val dwdGeometryCache: ServerDwdGeometryCache = ServerDwdGeometryCache(),
) {
    private val decoderWithoutDwdGeometry = EcCodesGribFieldDecoder(
        nativeSession = nativeSession,
        dwdGeometryProvider = DwdIconGridGeometryProvider {
            error("DWD geometry must be supplied explicitly")
        },
    )
    private val dwdGeometryDecoder = DwdIconGridGeometryDecoder(
        decompressor = dwdDecompressor,
        nativeSession = nativeSession,
    )

    suspend fun fetchNoaa(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): ServerForecastAdapterResult {
        requireLocationMatches(coordinate, location)
        val plan = NoaaGfsRequestPlanner.plan(
            modelRun = modelRun,
            coordinate = coordinate,
            forecastHour = forecastHour,
        )
        var mapped: SourceForecast? = null
        val result = gateway.execute(
            request = plan.request.toGatewayRequest(
                provider = plan.provider,
                modelFamily = plan.modelFamily,
            ),
            responseValidator = ProviderResponseValidator { response ->
                if (!response.matches(plan.provider, plan.modelFamily, 200)) {
                    false
                } else {
                    mapped = mapSafely {
                        mapper.map(
                            provider = plan.provider,
                            modelRun = plan.modelRun,
                            generatedAt = generatedAt,
                            location = location,
                            fields = decoderWithoutDwdGeometry.decode(
                                GribDecodeRequest.noaa(
                                    payload = response.body,
                                    plan = plan,
                                ),
                            ),
                        )
                    }
                    mapped != null
                }
            },
        )
        return result.toAdapterResult(mapped)
    }

    suspend fun fetchEcmwf(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): ServerForecastAdapterResult {
        requireLocationMatches(coordinate, location)
        val plan = EcmwfIfsRequestPlanner.plan(
            modelRun = modelRun,
            forecastHour = forecastHour,
        )

        var fieldPlan: EcmwfFieldRangePlan? = null
        val indexResult = gateway.execute(
            request = plan.indexRequest.toGatewayRequest(
                provider = plan.provider,
                modelFamily = plan.modelFamily,
            ),
            responseValidator = ProviderResponseValidator { response ->
                if (!response.matches(plan.provider, plan.modelFamily, 200)) {
                    false
                } else {
                    fieldPlan = try {
                        EcmwfIfsFieldSelector.select(
                            indexContent = response.body.toString(Charsets.UTF_8),
                            plan = plan,
                            fields = setOf(EcmwfSurfaceField.TEMPERATURE_2M),
                        ).single()
                    } catch (_: IllegalArgumentException) {
                        null
                    } catch (_: IllegalStateException) {
                        null
                    }
                    fieldPlan != null
                }
            },
        )
        if (indexResult is ProviderGatewayResult.Failure) {
            return indexResult.toAdapterFailure()
        }

        val selected = checkNotNull(fieldPlan) {
            "Successful ECMWF index validation must produce one selected field"
        }
        var mapped: SourceForecast? = null
        val fieldResult = gateway.execute(
            request = ProviderGatewayRequest(
                provider = selected.provider,
                modelFamily = selected.modelFamily,
                uri = selected.request.uri,
                maxResponseBytes = selected.request.maxResponseBytes,
                expectedStatusCodes = setOf(206),
                minimumRequestSpacing = selected.request.minimumRequestSpacing,
                byteRange = ProviderByteRange(
                    offset = selected.range.offset,
                    length = selected.range.length,
                ),
            ),
            responseValidator = ProviderResponseValidator { response ->
                if (!response.matches(selected.provider, selected.modelFamily, 206)) {
                    false
                } else {
                    mapped = mapSafely {
                        mapper.map(
                            provider = selected.provider,
                            modelRun = selected.modelRun,
                            generatedAt = generatedAt,
                            location = location,
                            fields = decoderWithoutDwdGeometry.decode(
                                GribDecodeRequest.ecmwf(
                                    payload = response.body,
                                    plan = selected,
                                    coordinate = coordinate,
                                ),
                            ),
                        )
                    }
                    mapped != null
                }
            },
        )
        return fieldResult.toAdapterResult(mapped)
    }

    suspend fun fetchDwd(
        modelRun: Instant,
        forecastHour: Int,
        coordinate: ForecastCoordinate,
        location: ForecastLocation,
        generatedAt: Instant,
    ): ServerForecastAdapterResult {
        requireLocationMatches(coordinate, location)
        val geometryPlan = DwdIconGridGeometryPlanner.plan(modelRun)
        val geometryResult = dwdGeometryCache.getOrLoad(geometryPlan) {
            loadDwdGeometry(geometryPlan)
        }
        val geometry = when (geometryResult) {
            is DwdGeometryLoadResult.Success -> geometryResult.geometry
            is DwdGeometryLoadResult.Failure -> return geometryResult.failure
        }

        val plan = DwdIconRequestPlanner.plan(
            modelRun = modelRun,
            forecastHour = forecastHour,
            field = DwdIconField.TEMPERATURE_2M,
        )
        val decoder = EcCodesGribFieldDecoder(
            nativeSession = nativeSession,
            dwdGeometryProvider = DwdIconGridGeometryProvider { requestedPlan ->
                require(requestedPlan.modelRun == geometry.modelRun) {
                    "DWD field requested geometry from another model run"
                }
                geometry
            },
        )

        var mapped: SourceForecast? = null
        val result = gateway.execute(
            request = plan.request.toGatewayRequest(
                provider = plan.provider,
                modelFamily = plan.modelFamily,
            ),
            responseValidator = ProviderResponseValidator { response ->
                if (!response.matches(plan.provider, plan.modelFamily, 200)) {
                    false
                } else {
                    mapped = mapSafely {
                        val payload = dwdDecompressor.decompress(response.body)
                        mapper.map(
                            provider = plan.provider,
                            modelRun = plan.modelRun,
                            generatedAt = generatedAt,
                            location = location,
                            fields = decoder.decode(
                                GribDecodeRequest.dwd(
                                    payload = payload,
                                    plan = plan,
                                    coordinate = coordinate,
                                    geometryPlan = geometryPlan,
                                ),
                            ),
                        )
                    }
                    mapped != null
                }
            },
        )
        return result.toAdapterResult(mapped)
    }

    private suspend fun loadDwdGeometry(
        plan: DwdIconGridGeometryPlan,
    ): DwdGeometryLoadResult {
        var latitudes: DoubleArray? = null
        val latitudeResult = gateway.execute(
            request = plan.latitudeRequest.toGatewayRequest(
                provider = ForecastProvider.DWD_OPEN_DATA,
                modelFamily = ModelFamily.DWD_ICON,
            ),
            responseValidator = ProviderResponseValidator { response ->
                if (!response.matches(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON, 200)) {
                    false
                } else {
                    latitudes = decodeSafely {
                        dwdGeometryDecoder.decodeLatitude(response.body)
                    }
                    latitudes != null
                }
            },
        )
        if (latitudeResult is ProviderGatewayResult.Failure) {
            return DwdGeometryLoadResult.Failure(latitudeResult.toAdapterFailure())
        }

        val latitudeValues = checkNotNull(latitudes) {
            "Successful DWD CLAT validation must produce coordinates"
        }
        var longitudes: DoubleArray? = null
        val longitudeResult = gateway.execute(
            request = plan.longitudeRequest.toGatewayRequest(
                provider = ForecastProvider.DWD_OPEN_DATA,
                modelFamily = ModelFamily.DWD_ICON,
            ),
            responseValidator = ProviderResponseValidator { response ->
                if (!response.matches(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON, 200)) {
                    false
                } else {
                    longitudes = decodeSafely {
                        dwdGeometryDecoder.decodeLongitude(response.body)
                    }?.takeIf { it.size == latitudeValues.size }
                    longitudes != null
                }
            },
        )
        if (longitudeResult is ProviderGatewayResult.Failure) {
            return DwdGeometryLoadResult.Failure(longitudeResult.toAdapterFailure())
        }

        val longitudeValues = checkNotNull(longitudes) {
            "Successful DWD CLON validation must produce coordinates"
        }
        val geometry = try {
            dwdGeometryDecoder.assemble(
                plan = plan,
                latitudes = latitudeValues,
                longitudesDegreesEast = longitudeValues,
            )
        } catch (_: IllegalArgumentException) {
            return DwdGeometryLoadResult.Failure(
                ServerForecastAdapterResult.Failure(
                    provider = ForecastProvider.DWD_OPEN_DATA,
                    modelFamily = ModelFamily.DWD_ICON,
                    reason = ServerForecastAdapterFailureReason.INVALID_RESPONSE,
                ),
            )
        }
        return DwdGeometryLoadResult.Success(geometry)
    }

    companion object {
        fun production(bundleRoot: Path): DirectOfficialServerForecastAdapters =
            DirectOfficialServerForecastAdapters(
                gateway = ServerProviderGateway.production(),
                nativeSession = ServerEcCodesRuntime.open(bundleRoot),
            )
    }
}

internal class ServerDwdGeometryCache {
    private val mutex = Mutex()
    private var cached: DwdIconGridGeometry? = null

    suspend fun getOrLoad(
        plan: DwdIconGridGeometryPlan,
        loader: suspend () -> DwdGeometryLoadResult,
    ): DwdGeometryLoadResult =
        mutex.withLock {
            cached?.takeIf { it.modelRun == plan.modelRun }?.let {
                return@withLock DwdGeometryLoadResult.Success(it)
            }
            loader().also { result ->
                if (result is DwdGeometryLoadResult.Success) {
                    cached = result.geometry
                }
            }
        }
}

internal sealed interface DwdGeometryLoadResult {
    data class Success(
        val geometry: DwdIconGridGeometry,
    ) : DwdGeometryLoadResult

    data class Failure(
        val failure: ServerForecastAdapterResult.Failure,
    ) : DwdGeometryLoadResult
}

private fun OfficialSourceRequest.toGatewayRequest(
    provider: ForecastProvider,
    modelFamily: ModelFamily,
): ProviderGatewayRequest =
    ProviderGatewayRequest(
        provider = provider,
        modelFamily = modelFamily,
        uri = uri,
        maxResponseBytes = maxResponseBytes,
        expectedStatusCodes = setOf(200),
        minimumRequestSpacing = minimumRequestSpacing,
    )

private fun ProviderGatewayResponse.matches(
    provider: ForecastProvider,
    modelFamily: ModelFamily,
    statusCode: Int,
): Boolean =
    this.provider == provider &&
        this.modelFamily == modelFamily &&
        this.statusCode == statusCode

private fun ProviderGatewayResult.toAdapterResult(
    mapped: SourceForecast?,
): ServerForecastAdapterResult =
    when (this) {
        is ProviderGatewayResult.Success ->
            ServerForecastAdapterResult.Success(
                checkNotNull(mapped) {
                    "Successful direct-official validation must produce a canonical forecast"
                },
            )

        is ProviderGatewayResult.Failure -> toAdapterFailure()
    }

private fun ProviderGatewayResult.Failure.toAdapterFailure(): ServerForecastAdapterResult.Failure =
    ServerForecastAdapterResult.Failure(
        provider = provider,
        modelFamily = modelFamily,
        reason = reason.toAdapterFailureReason(),
    )

private fun ProviderGatewayFailureReason.toAdapterFailureReason(): ServerForecastAdapterFailureReason =
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

private inline fun mapSafely(block: () -> SourceForecast): SourceForecast? =
    try {
        block()
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

private inline fun <T> decodeSafely(block: () -> T): T? =
    try {
        block()
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

private fun requireLocationMatches(
    coordinate: ForecastCoordinate,
    location: ForecastLocation,
) {
    require(
        location.latitude == coordinate.latitude &&
            location.longitude == coordinate.longitude
    ) {
        "Direct-official server location must match the privacy-normalized coordinate"
    }
}
