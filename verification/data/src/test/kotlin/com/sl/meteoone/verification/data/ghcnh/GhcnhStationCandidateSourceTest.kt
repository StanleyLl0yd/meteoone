package com.sl.meteoone.verification.data.ghcnh

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResponse
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class GhcnhStationCandidateSourceTest {
    private val target = ForecastCoordinate(59.9, 30.3)

    @Test
    fun fetchesOnlyCanonicalBoundedStationListAndRanksCandidates() {
        val transport = RecordingTransport(
            result = success(
                body = stationLine(
                    id = "RSM00026063",
                    latitude = "59.9667",
                    longitude = "30.3000",
                    elevation = "4.0",
                    name = "ST PETERSBURG",
                    wmo = "26063",
                    icao = "ULLI",
                ).toByteArray(),
            ),
        )

        val result = GhcnhStationCandidateSource(transport).candidates(
            target = target,
            targetElevationMeters = 10,
        )

        val request = transport.requests.single()
        assertEquals("https", request.uri.scheme)
        assertEquals(GHCNH_HOST, request.uri.host)
        assertEquals(GHCNH_STATION_LIST_PATH, request.uri.path)
        assertEquals(null, request.uri.query)
        assertEquals(GHCNH_STATION_LIST_MAX_RESPONSE_BYTES, request.maxResponseBytes)

        val available = assertIs<GhcnhStationCandidatesResult.Available>(result)
        assertEquals(listOf("RSM00026063"), available.candidates.map { it.station.stationId })
        assertEquals("26063", available.candidates.single().station.wmoId)
        assertEquals("ULLI", available.candidates.single().station.icao)
    }

    @Test
    fun transportHttpAndMalformedCatalogFailuresAreUnavailable() {
        val transportFailure = GhcnhStationCandidateSource(
            RecordingTransport(
                BoundedHttpsResult.Failure(
                    com.sl.meteoone.core.network.BoundedHttpsFailureReason.IO,
                ),
            ),
        )
        assertEquals(
            GhcnhStationCandidatesResult.Unavailable,
            transportFailure.candidates(target, null),
        )

        val httpFailure = GhcnhStationCandidateSource(
            RecordingTransport(success("not found".toByteArray(), statusCode = 404)),
        )
        assertEquals(
            GhcnhStationCandidatesResult.Unavailable,
            httpFailure.candidates(target, null),
        )

        val malformed = GhcnhStationCandidateSource(
            RecordingTransport(success("truncated".toByteArray())),
        )
        assertEquals(
            GhcnhStationCandidatesResult.Unavailable,
            malformed.candidates(target, null),
        )

        val invalidUtf8 = GhcnhStationCandidateSource(
            RecordingTransport(success(byteArrayOf(0xC3.toByte(), 0x28))),
        )
        assertEquals(
            GhcnhStationCandidatesResult.Unavailable,
            invalidUtf8.candidates(target, null),
        )
    }

    @Test
    fun validCatalogMayReturnNoEligibleCandidateWithoutLookingLikeTransportFailure() {
        val line = stationLine(
            id = "USW00094846",
            latitude = "41.9600",
            longitude = "-87.9317",
            elevation = "204.8",
            name = "CHICAGO OHARE INTL AP",
        )
        val result = GhcnhStationCandidateSource(
            RecordingTransport(success(line.toByteArray())),
        ).candidates(target, null)

        val available = assertIs<GhcnhStationCandidatesResult.Available>(result)
        assertEquals(emptyList(), available.candidates)
    }

    @Test
    fun requestObjectCannotBeRetargetedOrExpanded() {
        val canonical = GhcnhStationListRequestPlanner.plan()

        assertFailsWith<IllegalArgumentException> {
            canonical.copy(
                uri = URI.create(
                    "https://example.com$GHCNH_STATION_LIST_PATH",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            canonical.copy(
                uri = URI.create(
                    "https://$GHCNH_HOST$GHCNH_STATION_LIST_PATH?x=1",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            canonical.copy(
                maxResponseBytes = GHCNH_STATION_LIST_MAX_RESPONSE_BYTES + 1,
            )
        }
    }

    private class RecordingTransport(
        private val result: BoundedHttpsResult,
    ) : BoundedHttpsTransport {
        val requests = mutableListOf<BoundedHttpsRequest>()

        override fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall {
            requests += request
            return object : BoundedHttpsCall {
                override fun execute(): BoundedHttpsResult = result
                override fun cancel() = Unit
            }
        }
    }

    private fun success(
        body: ByteArray,
        statusCode: Int = 200,
    ): BoundedHttpsResult.Success = BoundedHttpsResult.Success(
        BoundedHttpsResponse(
            statusCode = statusCode,
            headers = emptyMap(),
            body = body,
        ),
    )

    private fun stationLine(
        id: String,
        latitude: String,
        longitude: String,
        elevation: String,
        name: String,
        wmo: String = "",
        icao: String = "",
    ): String {
        val chars = CharArray(90) { ' ' }
        put(chars, 0, 11, id)
        put(chars, 12, 20, latitude)
        put(chars, 21, 30, longitude)
        put(chars, 31, 37, elevation)
        put(chars, 41, 71, name)
        put(chars, 80, 85, wmo)
        put(chars, 86, 90, icao)
        return chars.concatToString()
    }

    private fun put(
        chars: CharArray,
        start: Int,
        end: Int,
        value: String,
    ) {
        require(value.length <= end - start)
        value.forEachIndexed { index, char -> chars[start + index] = char }
    }
}
