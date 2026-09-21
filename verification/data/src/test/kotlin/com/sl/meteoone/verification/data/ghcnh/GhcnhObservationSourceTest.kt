package com.sl.meteoone.verification.data.ghcnh

import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResponse
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GhcnhObservationSourceTest {
    @Test
    fun requestPlannerBindsExactNceiStationYearAndBound() {
        val request = GhcnhObservationRequestPlanner.plan("RSM00026063", 2026)

        assertEquals("https", request.uri.scheme)
        assertEquals(GHCNH_HOST, request.uri.host)
        assertEquals(
            "$GHCNH_OBSERVATION_ROOT_PATH/2026/psv/GHCNh_RSM00026063_2026.psv",
            request.uri.path,
        )
        assertEquals(GHCNH_OBSERVATION_MAX_RESPONSE_BYTES, request.maxResponseBytes)

        assertFailsWith<IllegalArgumentException> {
            request.copy(
                uri = URI.create(
                    "https://example.com$GHCNH_OBSERVATION_ROOT_PATH/2026/psv/" +
                        "GHCNh_RSM00026063_2026.psv",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(
                uri = URI.create(
                    "https://$GHCNH_HOST$GHCNH_OBSERVATION_ROOT_PATH/2025/psv/" +
                        "GHCNh_RSM00026063_2025.psv",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(
                maxResponseBytes = GHCNH_OBSERVATION_MAX_RESPONSE_BYTES + 1,
            )
        }
    }

    @Test
    fun fallsBackWhenNearestStationHasNoUsableRequestedPeriodEvidence() {
        val transport = RecordingTransport { request ->
            val station = stationFromPath(request.uri.path)
            when (station) {
                "RSM00026063" -> success(
                    fixture(
                        stationId = station,
                        date = "2026-09-20T12:00:00Z",
                        temperature = "99.0",
                        quality = "2",
                    ),
                )
                "USW00094846" -> success(
                    fixture(
                        stationId = station,
                        date = "2026-09-20T12:00:00Z",
                        temperature = "11.2",
                        quality = "5",
                    ),
                )
                else -> success("not found", 404)
            }
        }

        val result = GhcnhObservationSource(transport).observations(
            candidates = listOf(
                candidate("RSM00026063", 1.0),
                candidate("USW00094846", 2.0),
            ),
            startInclusive = Instant.parse("2026-09-20T00:00:00Z"),
            endExclusive = Instant.parse("2026-09-21T00:00:00Z"),
        )

        val available = assertIs<GhcnhObservationsResult.Available>(result)
        assertEquals("USW00094846", available.series.station.stationId)
        assertEquals(11.2, available.series.surfaceObservations.single().temperatureC)
        assertEquals(
            listOf("RSM00026063", "USW00094846"),
            transport.requests.map { stationFromPath(it.uri.path) },
        )
        assertTrue(
            transport.requests.all {
                it.uri.scheme == "https" &&
                    it.uri.host == GHCNH_HOST &&
                    it.maxResponseBytes == GHCNH_OBSERVATION_MAX_RESPONSE_BYTES
            },
        )
    }

    @Test
    fun crossesYearBoundaryUsingOnlyExactOverlappingYearFiles() {
        val transport = RecordingTransport { request ->
            if (request.uri.path.contains("/2025/")) {
                success("not found", 404)
            } else {
                success(
                    fixture(
                        stationId = "RSM00026063",
                        date = "2026-01-01T00:00:00Z",
                        temperature = "-3.0",
                        quality = "5",
                    ),
                )
            }
        }

        val result = GhcnhObservationSource(transport).observations(
            candidates = listOf(candidate("RSM00026063", 1.0)),
            startInclusive = Instant.parse("2025-12-31T23:00:00Z"),
            endExclusive = Instant.parse("2026-01-01T01:00:00Z"),
        )

        assertIs<GhcnhObservationsResult.Available>(result)
        assertEquals(
            listOf(2025, 2026),
            transport.requests.map { request ->
                request.uri.path
                    .substringAfter("$GHCNH_OBSERVATION_ROOT_PATH/")
                    .substringBefore("/")
                    .toInt()
            },
        )
    }

    @Test
    fun malformedOrFailedCandidatesEndAsExplicitUnavailable() {
        val malformed = RecordingTransport {
            success("Station_ID|Date\nRSM00026063|not-a-date")
        }
        assertEquals(
            GhcnhObservationsResult.Unavailable,
            GhcnhObservationSource(malformed).observations(
                listOf(candidate("RSM00026063", 1.0)),
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"),
            ),
        )

        val failed = RecordingTransport {
            BoundedHttpsResult.Failure(
                com.sl.meteoone.core.network.BoundedHttpsFailureReason.IO,
            )
        }
        assertEquals(
            GhcnhObservationsResult.Unavailable,
            GhcnhObservationSource(failed).observations(
                listOf(candidate("RSM00026063", 1.0)),
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"),
            ),
        )
    }

    @Test
    fun candidateAndRetentionBudgetsAreHardBounded() {
        val source = GhcnhObservationSource(
            RecordingTransport { success("not used", 500) },
        )

        assertFailsWith<IllegalArgumentException> {
            source.observations(
                candidates = (1..6).map { index ->
                    candidate("USW${index.toString().padStart(8, '0')}", index.toDouble())
                },
                startInclusive = Instant.parse("2026-01-01T00:00:00Z"),
                endExclusive = Instant.parse("2026-01-02T00:00:00Z"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            source.observations(
                candidates = listOf(candidate("RSM00026063", 1.0)),
                startInclusive = Instant.parse("2026-01-01T00:00:00Z"),
                endExclusive = Instant.parse("2026-07-01T00:00:00Z"),
            )
        }
    }

    private class RecordingTransport(
        private val responder: (BoundedHttpsRequest) -> BoundedHttpsResult,
    ) : BoundedHttpsTransport {
        val requests = mutableListOf<BoundedHttpsRequest>()

        override fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall {
            requests += request
            return object : BoundedHttpsCall {
                override fun execute(): BoundedHttpsResult = responder(request)
                override fun cancel() = Unit
            }
        }
    }

    private fun success(
        body: String,
        statusCode: Int = 200,
    ): BoundedHttpsResult.Success = BoundedHttpsResult.Success(
        BoundedHttpsResponse(
            statusCode = statusCode,
            headers = emptyMap(),
            body = body.toByteArray(),
        ),
    )

    private fun stationFromPath(path: String): String =
        path.substringAfter("/GHCNh_").substringBeforeLast("_")

    private fun candidate(
        id: String,
        distanceKm: Double,
    ): GhcnhStationCandidate = GhcnhStationCandidate(
        station = GhcnhStationMetadata(
            stationId = id,
            latitude = 59.9667,
            longitude = 30.3,
            elevationMeters = 4.0,
            state = null,
            name = id,
            gsn = false,
            hcnCrn = null,
            wmoId = null,
            icao = null,
        ),
        distanceKm = distanceKm,
        elevationDeltaMeters = null,
    )

    private fun fixture(
        stationId: String,
        date: String,
        temperature: String,
        quality: String,
    ): String {
        val header = listOf(
            "Station_ID",
            "Date",
            "Year",
            "Month",
            "Day",
            "Hour",
            "Minute",
            "Latitude",
            "Longitude",
            "Elevation",
            "temperature",
            "temperature_Measurement_Code",
            "temperature_Quality_Code",
            "temperature_Report_Type",
            "temperature_Source_Code",
            "temperature_Source_Station_ID",
        ).joinToString("|")
        val timestamp = Instant.parse(date).atOffset(java.time.ZoneOffset.UTC)
        val row = listOf(
            stationId,
            date,
            timestamp.year.toString(),
            timestamp.monthValue.toString(),
            timestamp.dayOfMonth.toString(),
            timestamp.hour.toString(),
            timestamp.minute.toString(),
            "59.9667",
            "30.3000",
            "4.0",
            temperature,
            "",
            quality,
            "METAR",
            "220",
            "26063-99999",
        ).joinToString("|")
        return "$header\n$row"
    }
}
