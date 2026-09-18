package com.sl.meteoone.verification.data.ghcnh

import com.sl.meteoone.core.model.ForecastCoordinate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val DEFAULT_MAX_CANDIDATES = 5
private const val MAX_ALLOWED_CANDIDATES = 20
private const val EARTH_RADIUS_KM = 6371.0088

class GhcnhStationCandidateRanker(
    private val maxDistanceKm: Double = 75.0,
    private val maxElevationDeltaMeters: Double = 300.0,
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
) {
    init {
        require(maxDistanceKm.isFinite() && maxDistanceKm > 0.0) {
            "GHCNh maximum station distance must be finite and positive"
        }
        require(
            maxElevationDeltaMeters.isFinite() &&
                maxElevationDeltaMeters >= 0.0,
        ) {
            "GHCNh maximum elevation delta must be finite and non-negative"
        }
        require(maxCandidates in 1..MAX_ALLOWED_CANDIDATES) {
            "GHCNh candidate count must be within 1..$MAX_ALLOWED_CANDIDATES"
        }
    }

    fun rank(
        target: ForecastCoordinate,
        targetElevationMeters: Int?,
        stations: List<GhcnhStationMetadata>,
    ): List<GhcnhStationCandidate> =
        stations.asSequence()
            .mapNotNull { station ->
                candidate(
                    target = target,
                    targetElevationMeters = targetElevationMeters,
                    station = station,
                )
            }
            .sortedWith(
                compareBy<GhcnhStationCandidate> { it.distanceKm }
                    .thenBy { it.elevationDeltaMeters ?: 0.0 }
                    .thenBy { it.station.stationId },
            )
            .take(maxCandidates)
            .toList()

    private fun candidate(
        target: ForecastCoordinate,
        targetElevationMeters: Int?,
        station: GhcnhStationMetadata,
    ): GhcnhStationCandidate? {
        val distanceKm = haversineKm(
            target.latitude,
            target.longitude,
            station.latitude,
            station.longitude,
        )
        if (distanceKm > maxDistanceKm) return null

        val elevationDelta = if (targetElevationMeters == null) {
            null
        } else {
            val stationElevation = station.elevationMeters ?: return null
            kotlin.math.abs(stationElevation - targetElevationMeters)
                .takeIf { it <= maxElevationDeltaMeters }
                ?: return null
        }

        return GhcnhStationCandidate(
            station = station,
            distanceKm = distanceKm,
            elevationDeltaMeters = elevationDelta,
        )
    }
}

internal fun haversineKm(
    latitudeA: Double,
    longitudeA: Double,
    latitudeB: Double,
    longitudeB: Double,
): Double {
    val phiA = Math.toRadians(latitudeA)
    val phiB = Math.toRadians(latitudeB)
    val deltaPhi = Math.toRadians(latitudeB - latitudeA)
    val deltaLambda = Math.toRadians(longitudeB - longitudeA)
    val a =
        sin(deltaPhi / 2.0) * sin(deltaPhi / 2.0) +
            cos(phiA) * cos(phiB) *
            sin(deltaLambda / 2.0) * sin(deltaLambda / 2.0)
    return 2.0 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
