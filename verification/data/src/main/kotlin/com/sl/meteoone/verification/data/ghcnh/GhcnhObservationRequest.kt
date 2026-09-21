package com.sl.meteoone.verification.data.ghcnh

import java.net.URI

internal const val GHCNH_OBSERVATION_MAX_RESPONSE_BYTES = 32L * 1024L * 1024L
internal const val GHCNH_OBSERVATION_ROOT_PATH =
    "/oa/global-historical-climatology-network/hourly/access/by-year"

internal data class GhcnhObservationRequest(
    val stationId: String,
    val year: Int,
    val uri: URI,
    val maxResponseBytes: Long = GHCNH_OBSERVATION_MAX_RESPONSE_BYTES,
) {
    init {
        require(stationId.matches(Regex("^[A-Z0-9]{11}$"))) {
            "GHCNh observation station id is invalid"
        }
        require(year in 1790..9999) {
            "GHCNh observation year is out of bounds"
        }
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "GHCNh observation requests must use HTTPS"
        }
        require(uri.host == GHCNH_HOST) {
            "GHCNh observation request host must be $GHCNH_HOST"
        }
        require(uri.port == -1 || uri.port == 443) {
            "GHCNh observation requests must use the default HTTPS port"
        }
        require(uri.userInfo == null) {
            "GHCNh observation requests must not contain user info"
        }
        val expectedPath =
            "$GHCNH_OBSERVATION_ROOT_PATH/$year/psv/GHCNh_${stationId}_$year.psv"
        require(uri.path == expectedPath) {
            "GHCNh observation request path does not match station/year identity"
        }
        require(uri.rawQuery == null) {
            "GHCNh observation requests must not contain a query"
        }
        require(uri.fragment == null) {
            "GHCNh observation requests must not contain fragments"
        }
        require(maxResponseBytes in 1..GHCNH_OBSERVATION_MAX_RESPONSE_BYTES) {
            "GHCNh observation response byte limit is out of bounds"
        }
    }
}

internal object GhcnhObservationRequestPlanner {
    fun plan(
        stationId: String,
        year: Int,
    ): GhcnhObservationRequest {
        val path =
            "$GHCNH_OBSERVATION_ROOT_PATH/$year/psv/GHCNh_${stationId}_$year.psv"
        return GhcnhObservationRequest(
            stationId = stationId,
            year = year,
            uri = URI.create("https://$GHCNH_HOST$path"),
        )
    }
}
