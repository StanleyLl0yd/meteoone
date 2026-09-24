package com.sl.meteoone.backend.gateway

import com.sl.meteoone.core.model.ForecastTarget
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Privacy-safe identity for one backend forecast result.
 *
 * The target already contains only the canonical 0.1-degree forecast coordinate. The time bucket
 * mirrors the existing 72-hour forecast start-hour contract and avoids raw request timestamps in
 * cache identity.
 */
data class ForecastGatewayCacheKey(
    val target: ForecastTarget,
    val forecastStartHour: Instant,
) {
    init {
        require(forecastStartHour == forecastStartHour.truncatedTo(ChronoUnit.HOURS)) {
            "Gateway forecast start hour must be aligned to an exact UTC hour"
        }
    }

    companion object {
        fun from(
            target: ForecastTarget,
            requestedAt: Instant,
        ): ForecastGatewayCacheKey =
            ForecastGatewayCacheKey(
                target = target,
                forecastStartHour = requestedAt.toForecastStartHour(),
            )
    }
}

private fun Instant.toForecastStartHour(): Instant {
    val truncated = truncatedTo(ChronoUnit.HOURS)
    return if (this == truncated) truncated else truncated.plus(1, ChronoUnit.HOURS)
}
