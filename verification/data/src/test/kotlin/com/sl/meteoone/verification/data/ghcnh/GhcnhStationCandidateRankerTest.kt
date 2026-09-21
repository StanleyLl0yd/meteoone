package com.sl.meteoone.verification.data.ghcnh

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.verification.domain.ObservationStation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GhcnhStationCandidateRankerTest {
    private val target = ForecastCoordinate(59.9, 30.3)

    @Test
    fun ranksNearestEligibleStationsDeterministically() {
        val stations = listOf(
            station("RSM00000003", 59.9, 30.5, 20.0),
            station("RSM00000002", 59.9, 30.4, 25.0),
            station("RSM00000001", 59.9, 30.4, 25.0),
        )

        val candidates = GhcnhStationCandidateRanker().rank(
            target = target,
            targetElevationMeters = 20,
            stations = stations,
        )

        assertEquals(
            listOf("RSM00000001", "RSM00000002", "RSM00000003"),
            candidates.map { it.station.stationId },
        )
        assertTrue(candidates.zipWithNext().all { (left, right) ->
            left.distanceKm <= right.distanceKm
        })
    }

    @Test
    fun persistedObservationStationsReuseTheSameDistanceAndElevationBounds() {
        val stations = listOf(
            ObservationStation("noaa-ncei-ghcnh", "RSM00000003", 59.9, 30.5, 20.0),
            ObservationStation("noaa-ncei-ghcnh", "RSM00000002", 59.9, 30.4, 25.0),
            ObservationStation("noaa-ncei-ghcnh", "RSM00000001", 59.9, 30.4, 25.0),
            ObservationStation("noaa-ncei-ghcnh", "RSM99999999", 61.0, 30.3, 20.0),
        )

        val ranked = GhcnhStationCandidateRanker().rankStoredStations(
            target = target,
            targetElevationMeters = 20,
            stations = stations,
        )

        assertEquals(
            listOf("RSM00000001", "RSM00000002", "RSM00000003"),
            ranked.map(ObservationStation::stationId),
        )
    }

    @Test
    fun knownTargetElevationRejectsUnknownOrDistantElevation() {
        val stations = listOf(
            station("RSM00000001", 59.9, 30.3, null),
            station("RSM00000002", 59.9, 30.31, 321.0),
            station("RSM00000003", 59.9, 30.32, 319.0),
        )

        val candidates = GhcnhStationCandidateRanker().rank(
            target = target,
            targetElevationMeters = 20,
            stations = stations,
        )

        assertEquals(listOf("RSM00000003"), candidates.map { it.station.stationId })
        assertEquals(299.0, candidates.single().elevationDeltaMeters)
    }

    @Test
    fun unknownTargetElevationDoesNotRequireStationElevation() {
        val candidates = GhcnhStationCandidateRanker().rank(
            target = target,
            targetElevationMeters = null,
            stations = listOf(
                station("RSM00000001", 59.9, 30.3, null),
            ),
        )

        assertEquals(1, candidates.size)
        assertEquals(null, candidates.single().elevationDeltaMeters)
    }

    @Test
    fun distanceAndCandidateCountAreBounded() {
        val stations = (1..8).map { index ->
            station(
                id = "RSM${index.toString().padStart(8, '0')}",
                latitude = 59.9,
                longitude = 30.3 + index * 0.01,
                elevation = 20.0,
            )
        } + station(
            id = "RSM99999999",
            latitude = 61.0,
            longitude = 30.3,
            elevation = 20.0,
        )

        val candidates = GhcnhStationCandidateRanker(
            maxDistanceKm = 75.0,
            maxCandidates = 5,
        ).rank(
            target = target,
            targetElevationMeters = 20,
            stations = stations,
        )

        assertEquals(5, candidates.size)
        assertTrue(candidates.all { it.distanceKm <= 75.0 })
        assertTrue(candidates.none { it.station.stationId == "RSM99999999" })
    }

    @Test
    fun invalidRankerBoundsFailBeforeRanking() {
        assertFailsWith<IllegalArgumentException> {
            GhcnhStationCandidateRanker(maxDistanceKm = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            GhcnhStationCandidateRanker(maxElevationDeltaMeters = -1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            GhcnhStationCandidateRanker(maxCandidates = 21)
        }
    }

    @Test
    fun privacyTargetCannotCarryRawCoordinatePrecision() {
        assertFailsWith<IllegalArgumentException> {
            ForecastCoordinate(59.94, 30.31)
        }
    }

    @Test
    fun haversineHandlesAntimeridianWithoutLongitudeAlias() {
        val distance = haversineKm(
            latitudeA = 0.0,
            longitudeA = 179.9,
            latitudeB = 0.0,
            longitudeB = -179.9,
        )

        assertTrue(distance in 22.0..22.4)
    }

    private fun station(
        id: String,
        latitude: Double,
        longitude: Double,
        elevation: Double?,
    ): GhcnhStationMetadata = GhcnhStationMetadata(
        stationId = id,
        latitude = latitude,
        longitude = longitude,
        elevationMeters = elevation,
        state = null,
        name = id,
        gsn = false,
        hcnCrn = null,
        wmoId = null,
        icao = null,
    )
}
