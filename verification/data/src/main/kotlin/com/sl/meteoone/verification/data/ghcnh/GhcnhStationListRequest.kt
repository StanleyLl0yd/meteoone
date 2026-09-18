package com.sl.meteoone.verification.data.ghcnh

import java.net.URI

internal const val GHCNH_STATION_LIST_MAX_RESPONSE_BYTES = 8L * 1024L * 1024L
internal const val GHCNH_HOST = "www.ncei.noaa.gov"
internal const val GHCNH_STATION_LIST_PATH =
    "/oa/global-historical-climatology-network/hourly/doc/ghcnh-station-list.txt"

internal data class GhcnhStationListRequest(
    val uri: URI,
    val maxResponseBytes: Long = GHCNH_STATION_LIST_MAX_RESPONSE_BYTES,
) {
    init {
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "GHCNh station-list requests must use HTTPS"
        }
        require(uri.host == GHCNH_HOST) {
            "GHCNh station-list request host must be $GHCNH_HOST"
        }
        require(uri.port == -1 || uri.port == 443) {
            "GHCNh station-list requests must use the default HTTPS port"
        }
        require(uri.userInfo == null) {
            "GHCNh station-list requests must not contain user info"
        }
        require(uri.path == GHCNH_STATION_LIST_PATH) {
            "GHCNh station-list request path must be $GHCNH_STATION_LIST_PATH"
        }
        require(uri.rawQuery == null) {
            "GHCNh station-list requests must not contain a query"
        }
        require(uri.fragment == null) {
            "GHCNh station-list requests must not contain fragments"
        }
        require(maxResponseBytes in 1..GHCNH_STATION_LIST_MAX_RESPONSE_BYTES) {
            "GHCNh station-list response byte limit is out of bounds"
        }
    }
}

internal object GhcnhStationListRequestPlanner {
    private const val URL = "https://$GHCNH_HOST$GHCNH_STATION_LIST_PATH"

    fun plan(): GhcnhStationListRequest =
        GhcnhStationListRequest(uri = URI.create(URL))
}
