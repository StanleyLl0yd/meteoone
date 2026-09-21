package com.sl.meteoone.verification.data.ghcnh

import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.verification.domain.ObservationStation
import com.sl.meteoone.verification.domain.PrecipitationObservation
import com.sl.meteoone.verification.domain.SurfaceObservation
import java.time.Duration
import java.time.Instant

const val GHCNH_SOURCE_ID = "noaa-ncei-ghcnh"

data class GhcnhValueAttributes(
    val measurementCode: String?,
    val qualityCode: String?,
    val reportType: String?,
    val sourceCode: String?,
    val sourceStationId: String?,
)

data class GhcnhValueEvidence(
    val value: Double?,
    val attributes: GhcnhValueAttributes,
) {
    fun isExplicitlyUsable(): Boolean {
        if (value == null || !value.isFinite()) return false
        val quality = attributes.qualityCode?.trim().orEmpty()
        return quality.isEmpty() || quality == "1" || quality == "5"
    }
}

enum class GhcnhVariable(
    val header: String,
    val precipitationDuration: Duration? = null,
) {
    TEMPERATURE("temperature"),
    SEA_LEVEL_PRESSURE("sea_level_pressure"),
    WIND_SPEED("wind_speed"),
    WIND_DIRECTION("wind_direction"),

    /**
     * Kept as raw evidence only. The generic GHCNh precipitation field may represent
     * different accumulation periods, so M4 never invents an interval for it.
     */
    PRECIPITATION("precipitation"),
    PRECIPITATION_5_MINUTE("precipitation_5_minute", Duration.ofMinutes(5)),
    PRECIPITATION_15_MINUTE("precipitation_15_minute", Duration.ofMinutes(15)),
    PRECIPITATION_3_HOUR("precipitation_3_hour", Duration.ofHours(3)),
    PRECIPITATION_6_HOUR("precipitation_6_hour", Duration.ofHours(6)),
    PRECIPITATION_9_HOUR("precipitation_9_hour", Duration.ofHours(9)),
    PRECIPITATION_12_HOUR("precipitation_12_hour", Duration.ofHours(12)),
    PRECIPITATION_15_HOUR("precipitation_15_hour", Duration.ofHours(15)),
    PRECIPITATION_18_HOUR("precipitation_18_hour", Duration.ofHours(18)),
    PRECIPITATION_21_HOUR("precipitation_21_hour", Duration.ofHours(21)),
    PRECIPITATION_24_HOUR("precipitation_24_hour", Duration.ofHours(24)),
}

data class GhcnhRawObservation(
    val stationId: String,
    val observedAt: Instant,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val values: Map<GhcnhVariable, GhcnhValueEvidence>,
) {
    init {
        require(stationId.matches(Regex("^[A-Z0-9]{11}$"))) {
            "GHCNh observation station id is invalid"
        }
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "GHCNh observation latitude is invalid"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "GHCNh observation longitude is invalid"
        }
        require(elevationMeters == null || elevationMeters.isFinite()) {
            "GHCNh observation elevation must be finite when present"
        }
    }
}

data class GhcnhObservationSeries(
    val stationMetadata: GhcnhStationMetadata,
    val station: ObservationStation,
    val surfaceObservations: List<SurfaceObservation>,
    val precipitationObservations: List<PrecipitationObservation>,
    val evidence: List<GhcnhRawObservation>,
) {
    init {
        require(station.sourceId == GHCNH_SOURCE_ID) {
            "GHCNh canonical station must retain GHCNh source identity"
        }
        require(station.stationId == stationMetadata.stationId) {
            "GHCNh canonical station id must match selected station metadata"
        }
        require(surfaceObservations.zipWithNext().all { (left, right) ->
            left.observedAt.isBefore(right.observedAt)
        }) {
            "GHCNh surface observations must be strictly chronological"
        }
        require(precipitationObservations.sortedWith(PRECIPITATION_ORDER) == precipitationObservations) {
            "GHCNh precipitation observations must be deterministically ordered"
        }
        require(evidence.zipWithNext().all { (left, right) ->
            !left.observedAt.isAfter(right.observedAt)
        }) {
            "GHCNh raw evidence must be chronological"
        }
    }

    val hasUsableEvidence: Boolean
        get() = surfaceObservations.isNotEmpty() || precipitationObservations.isNotEmpty()
}

sealed interface GhcnhObservationsResult {
    data class Available(
        val series: GhcnhObservationSeries,
    ) : GhcnhObservationsResult

    data object Unavailable : GhcnhObservationsResult
}

internal val PRECIPITATION_ORDER =
    compareBy<PrecipitationObservation> { it.interval.end }
        .thenBy { it.interval.start }

internal fun precipitationInterval(
    observedAt: Instant,
    duration: Duration,
): ForecastInterval = ForecastInterval(
    start = observedAt.minus(duration),
    end = observedAt,
)
