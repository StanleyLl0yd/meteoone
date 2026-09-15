package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.data.dwd.DwdIconGridGeometryPlan
import com.sl.meteoone.forecast.data.dwd.DwdIconRequestPlan
import com.sl.meteoone.forecast.data.ecmwf.EcmwfFieldRangePlan
import com.sl.meteoone.forecast.data.noaa.NoaaGfsRequestPlan
import java.time.Instant
import kotlin.ConsistentCopyVisibility

private const val MAX_DECODED_GRIB_PAYLOAD_BYTES = 64 * 1024 * 1024

internal enum class GribForecastParameter {
    TEMPERATURE_2M,
    DEW_POINT_2M,
    RELATIVE_HUMIDITY_2M,
    PRESSURE_MEAN_SEA_LEVEL,
    WIND_U_10M,
    WIND_V_10M,
    WIND_GUST_10M,
    PRECIPITATION_ACCUMULATION,
    TOTAL_CLOUD_COVER,
    VISIBILITY,
}

internal enum class GribValueUnit {
    KELVIN,
    PASCAL,
    METRES_PER_SECOND,
    METRES,
    KILOGRAMS_PER_SQUARE_METRE,
    PERCENT,
}

internal data class DecodedGribField(
    val parameter: GribForecastParameter,
    val value: Double,
    val unit: GribValueUnit,
    val validTime: Instant,
    val intervalStart: Instant? = null,
) {
    init {
        require(value.isFinite()) { "Decoded GRIB value must be finite" }
        require(intervalStart == null || intervalStart.isBefore(validTime)) {
            "GRIB interval must start before its valid time"
        }
        require(
            intervalStart == null ||
                parameter == GribForecastParameter.WIND_GUST_10M ||
                parameter == GribForecastParameter.PRECIPITATION_ACCUMULATION,
        ) {
            "GRIB interval metadata is only supported for gust maxima and precipitation accumulation"
        }
    }
}

/**
 * Provider-bound context for one official GRIB decode.
 *
 * Only privacy-normalized coordinates or provider-side snapped grid points reach this boundary.
 * Raw device coordinates must never be carried by these types.
 */
internal sealed interface OfficialGribDecodeContext {
    val provider: ForecastProvider
    val modelFamily: ModelFamily
    val modelRun: Instant
    val validTime: Instant

    @ConsistentCopyVisibility
    data class Noaa internal constructor(
        val plan: NoaaGfsRequestPlan,
    ) : OfficialGribDecodeContext {
        override val provider: ForecastProvider = plan.provider
        override val modelFamily: ModelFamily = plan.modelFamily
        override val modelRun: Instant = plan.modelRun
        override val validTime: Instant = plan.validTime
    }

    @ConsistentCopyVisibility
    data class Ecmwf internal constructor(
        val plan: EcmwfFieldRangePlan,
        val coordinate: ForecastCoordinate,
    ) : OfficialGribDecodeContext {
        override val provider: ForecastProvider = plan.provider
        override val modelFamily: ModelFamily = plan.modelFamily
        override val modelRun: Instant = plan.modelRun
        override val validTime: Instant = plan.validTime
    }

    @ConsistentCopyVisibility
    data class Dwd internal constructor(
        val plan: DwdIconRequestPlan,
        val coordinate: ForecastCoordinate,
        val geometryPlan: DwdIconGridGeometryPlan,
    ) : OfficialGribDecodeContext {
        init {
            require(geometryPlan.modelRun == plan.modelRun) {
                "DWD grid geometry must belong to the same model run as the forecast field"
            }
        }

        override val provider: ForecastProvider = plan.provider
        override val modelFamily: ModelFamily = plan.modelFamily
        override val modelRun: Instant = plan.modelRun
        override val validTime: Instant = plan.validTime
    }
}

internal class GribDecodeRequest private constructor(
    val payload: ByteArray,
    val context: OfficialGribDecodeContext,
) {
    init {
        require(payload.isNotEmpty()) { "GRIB payload must not be empty" }
        require(payload.size <= MAX_DECODED_GRIB_PAYLOAD_BYTES) {
            "GRIB payload exceeds the bounded decode limit"
        }
    }

    companion object {
        fun noaa(
            payload: ByteArray,
            plan: NoaaGfsRequestPlan,
        ): GribDecodeRequest = GribDecodeRequest(
            payload = payload,
            context = OfficialGribDecodeContext.Noaa(plan),
        )

        fun ecmwf(
            payload: ByteArray,
            plan: EcmwfFieldRangePlan,
            coordinate: ForecastCoordinate,
        ): GribDecodeRequest = GribDecodeRequest(
            payload = payload,
            context = OfficialGribDecodeContext.Ecmwf(plan, coordinate),
        )

        fun dwd(
            payload: ByteArray,
            plan: DwdIconRequestPlan,
            coordinate: ForecastCoordinate,
            geometryPlan: DwdIconGridGeometryPlan,
        ): GribDecodeRequest = GribDecodeRequest(
            payload = payload,
            context = OfficialGribDecodeContext.Dwd(plan, coordinate, geometryPlan),
        )
    }
}

internal fun interface GribFieldDecoder {
    fun decode(request: GribDecodeRequest): List<DecodedGribField>
}
