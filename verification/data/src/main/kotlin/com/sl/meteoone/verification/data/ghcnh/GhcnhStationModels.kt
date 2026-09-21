package com.sl.meteoone.verification.data.ghcnh

data class GhcnhStationMetadata(
    val stationId: String,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val state: String?,
    val name: String,
    val gsn: Boolean,
    val hcnCrn: GhcnhClimateNetwork?,
    val wmoId: String?,
    val icao: String?,
) {
    init {
        require(STATION_ID.matches(stationId)) {
            "GHCNh station id must contain exactly 11 uppercase alphanumeric characters"
        }
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "GHCNh station latitude is invalid"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "GHCNh station longitude is invalid"
        }
        require(
            elevationMeters == null ||
                (elevationMeters.isFinite() && elevationMeters in -500.0..9000.0),
        ) {
            "GHCNh station elevation is invalid"
        }
        require(name.isNotBlank()) { "GHCNh station name must not be blank" }
        require(state == null || STATE.matches(state)) {
            "GHCNh state code must contain exactly two uppercase letters when present"
        }
        require(wmoId == null || WMO_ID.matches(wmoId)) {
            "GHCNh WMO id must contain exactly five digits when present"
        }
        require(icao == null || ICAO.matches(icao)) {
            "GHCNh ICAO id must contain exactly four uppercase alphanumeric characters when present"
        }
    }

    private companion object {
        val STATION_ID = Regex("^[A-Z0-9]{11}$")
        val STATE = Regex("^[A-Z]{2}$")
        val WMO_ID = Regex("^\\d{5}$")
        val ICAO = Regex("^[A-Z0-9]{4}$")
    }
}

enum class GhcnhClimateNetwork {
    HCN,
    CRN,
}

data class GhcnhStationCandidate(
    val station: GhcnhStationMetadata,
    val distanceKm: Double,
    val elevationDeltaMeters: Double?,
) {
    init {
        require(distanceKm.isFinite() && distanceKm >= 0.0) {
            "GHCNh station distance must be finite and non-negative"
        }
        require(elevationDeltaMeters == null || elevationDeltaMeters >= 0.0) {
            "GHCNh station elevation delta must not be negative"
        }
    }
}
