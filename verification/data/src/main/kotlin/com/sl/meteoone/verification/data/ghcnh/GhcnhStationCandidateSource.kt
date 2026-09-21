package com.sl.meteoone.verification.data.ghcnh

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import com.sl.meteoone.core.network.DefaultBoundedHttpsTransport
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface GhcnhStationCandidatesResult {
    data class Available(
        val candidates: List<GhcnhStationCandidate>,
    ) : GhcnhStationCandidatesResult

    data object Unavailable : GhcnhStationCandidatesResult
}

class GhcnhStationCandidateSource internal constructor(
    private val transport: BoundedHttpsTransport,
    private val ranker: GhcnhStationCandidateRanker = GhcnhStationCandidateRanker(),
) {
    fun candidates(
        target: ForecastCoordinate,
        targetElevationMeters: Int?,
    ): GhcnhStationCandidatesResult {
        val request = GhcnhStationListRequestPlanner.plan()
        val networkRequest = BoundedHttpsRequest(
            uri = request.uri,
            maxResponseBytes = request.maxResponseBytes,
        )
        val response = when (val result = transport.newCall(networkRequest).execute()) {
            is BoundedHttpsResult.Failure -> return GhcnhStationCandidatesResult.Unavailable
            is BoundedHttpsResult.Success -> result.response
        }
        if (response.statusCode != 200) {
            return GhcnhStationCandidatesResult.Unavailable
        }

        val stations = try {
            GhcnhStationListParser.parse(decodeStrictUtf8(response.body))
        } catch (_: IllegalArgumentException) {
            return GhcnhStationCandidatesResult.Unavailable
        } catch (_: java.nio.charset.CharacterCodingException) {
            return GhcnhStationCandidatesResult.Unavailable
        }

        return GhcnhStationCandidatesResult.Available(
            candidates = ranker.rank(
                target = target,
                targetElevationMeters = targetElevationMeters,
                stations = stations,
            ),
        )
    }

    companion object {
        fun default(): GhcnhStationCandidateSource =
            GhcnhStationCandidateSource(DefaultBoundedHttpsTransport())
    }
}

private fun decodeStrictUtf8(bytes: ByteArray): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
        .removePrefix("\uFEFF")
