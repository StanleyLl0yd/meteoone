package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.forecast.data.dwd.BoundedDwdBzip2Decompressor
import com.sl.meteoone.forecast.data.dwd.DwdIconGridGeometryPlan
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import com.sl.meteoone.forecast.data.transport.ForecastHttpAdapter

/**
 * Loads DWD ICON CLAT/CLON geometry for exactly one validated model run at a time.
 *
 * The cache is deliberately process-memory only. A geometry is published only after
 * both coordinate fields have been fetched and the shared JVM geometry decoder has
 * validated them for the same [DwdIconGridGeometryPlan].
 */
internal class RunScopedDwdIconGridGeometryProvider(
    private val httpAdapter: ForecastHttpAdapter,
    decompressor: BoundedDwdBzip2Decompressor,
    nativeSession: EcCodesNativeSession,
) : DwdIconGridGeometryProvider {
    private val loadLock = Any()
    private val decoder = DwdIconGridGeometryDecoder(
        decompressor = decompressor,
        nativeSession = nativeSession,
    )

    @Volatile
    private var cachedGeometry: DwdIconGridGeometry? = null

    override fun geometryFor(plan: DwdIconGridGeometryPlan): DwdIconGridGeometry {
        cachedGeometry?.takeIf { it.modelRun == plan.modelRun }?.let { return it }

        return synchronized(loadLock) {
            cachedGeometry?.takeIf { it.modelRun == plan.modelRun }?.let { return@synchronized it }

            val latitude = load(plan.latitudeRequest, "CLAT")
            val longitude = load(plan.longitudeRequest, "CLON")
            decoder.decode(
                plan = plan,
                compressedLatitude = latitude,
                compressedLongitude = longitude,
            ).also { cachedGeometry = it }
        }
    }

    private fun load(
        request: OfficialSourceRequest,
        label: String,
    ): ByteArray =
        when (val result = httpAdapter.newOrdinaryCall(request).execute()) {
            is BoundedHttpsResult.Success -> result.response.body
            is BoundedHttpsResult.Failure -> throw IllegalStateException(
                "DWD ICON $label transport failed: ${result.reason}",
            )
        }
}
