package com.sl.meteoone.verification.data.ghcnh

import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import com.sl.meteoone.core.network.DefaultBoundedHttpsTransport
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private const val MAX_OBSERVATION_CANDIDATES = 5
private val MAX_OBSERVATION_WINDOW: Duration = Duration.ofDays(180)

class GhcnhObservationSource internal constructor(
    private val transport: BoundedHttpsTransport,
) {
    fun observations(
        candidates: List<GhcnhStationCandidate>,
        startInclusive: Instant,
        endExclusive: Instant,
    ): GhcnhObservationsResult {
        require(candidates.size <= MAX_OBSERVATION_CANDIDATES) {
            "GHCNh observation candidate count exceeds the production fallback bound"
        }
        require(candidates.map { it.station.stationId }.distinct().size == candidates.size) {
            "GHCNh observation candidates must have unique station ids"
        }
        require(startInclusive.isBefore(endExclusive)) {
            "GHCNh observation window start must precede end"
        }
        require(Duration.between(startInclusive, endExclusive) <= MAX_OBSERVATION_WINDOW) {
            "GHCNh observation window exceeds the 180-day verification retention bound"
        }
        if (candidates.isEmpty()) return GhcnhObservationsResult.Unavailable

        val years = requestedYears(startInclusive, endExclusive)
        candidates.forEach { candidate ->
            val raw = ArrayList<GhcnhRawObservation>()
            years.forEach { year ->
                fetchYear(candidate.station.stationId, year)?.let(raw::addAll)
            }
            val series = GhcnhObservationNormalizer.normalize(
                candidate = candidate,
                observations = raw,
                startInclusive = startInclusive,
                endExclusive = endExclusive,
            )
            if (series.hasUsableEvidence) {
                return GhcnhObservationsResult.Available(series)
            }
        }
        return GhcnhObservationsResult.Unavailable
    }

    private fun fetchYear(
        stationId: String,
        year: Int,
    ): List<GhcnhRawObservation>? {
        val request = GhcnhObservationRequestPlanner.plan(stationId, year)
        val response = when (
            val result = transport.newCall(
                BoundedHttpsRequest(
                    uri = request.uri,
                    maxResponseBytes = request.maxResponseBytes,
                ),
            ).execute()
        ) {
            is BoundedHttpsResult.Failure -> return null
            is BoundedHttpsResult.Success -> result.response
        }
        if (response.statusCode != 200) return null

        return try {
            GhcnhObservationParser.parse(
                content = decodeStrictUtf8(response.body),
                expectedStationId = stationId,
                expectedYear = year,
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    companion object {
        fun default(): GhcnhObservationSource =
            GhcnhObservationSource(DefaultBoundedHttpsTransport())
    }
}

private fun requestedYears(
    startInclusive: Instant,
    endExclusive: Instant,
): IntRange {
    val first = startInclusive.atOffset(ZoneOffset.UTC).year
    val lastIncluded = endExclusive.minusNanos(1)
    val last = lastIncluded.atOffset(ZoneOffset.UTC).year
    return first..last
}

private fun decodeStrictUtf8(bytes: ByteArray): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
        .removePrefix("\uFEFF")
