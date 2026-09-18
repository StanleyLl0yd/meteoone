package com.sl.meteoone.verification.data.ghcnh

internal object GhcnhStationListParser {
    private const val MIN_REQUIRED_COLUMNS = 71
    private const val MISSING_ELEVATION = -999.9

    fun parse(content: String): List<GhcnhStationMetadata> {
        val stations = ArrayList<GhcnhStationMetadata>()
        val seenIds = HashSet<String>()

        content.lineSequence().forEachIndexed { zeroBasedLine, rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank()) return@forEachIndexed
            require(line.length >= MIN_REQUIRED_COLUMNS) {
                "GHCNh station list line ${zeroBasedLine + 1} is truncated"
            }

            val station = parseLine(line, zeroBasedLine + 1)
            require(seenIds.add(station.stationId)) {
                "GHCNh station list contains duplicate station id ${station.stationId}"
            }
            stations += station
        }

        require(stations.isNotEmpty()) { "GHCNh station list must not be empty" }
        return stations
    }

    private fun parseLine(
        line: String,
        lineNumber: Int,
    ): GhcnhStationMetadata {
        val stationId = line.field(0, 11)
        val latitude = line.requiredFiniteDouble(12, 20, "latitude", lineNumber)
        val longitude = line.requiredFiniteDouble(21, 30, "longitude", lineNumber)
        val elevationRaw = line.requiredFiniteDouble(31, 37, "elevation", lineNumber)
        val state = line.field(38, 40).ifBlank { null }
        val name = line.field(41, 71)
        val gsn = when (val value = line.field(72, 75)) {
            "" -> false
            "GSN" -> true
            else -> throw IllegalArgumentException(
                "GHCNh station list line $lineNumber has unknown GSN flag: $value",
            )
        }
        val hcnCrn = when (val value = line.field(76, 79)) {
            "" -> null
            "HCN" -> GhcnhClimateNetwork.HCN
            "CRN" -> GhcnhClimateNetwork.CRN
            else -> throw IllegalArgumentException(
                "GHCNh station list line $lineNumber has unknown HCN/CRN flag: $value",
            )
        }
        val wmoId = line.field(80, 85).ifBlank { null }
        val icao = line.field(86, 90).ifBlank { null }

        return try {
            GhcnhStationMetadata(
                stationId = stationId,
                latitude = latitude,
                longitude = longitude,
                elevationMeters = elevationRaw.takeUnless { it == MISSING_ELEVATION },
                state = state,
                name = name,
                gsn = gsn,
                hcnCrn = hcnCrn,
                wmoId = wmoId,
                icao = icao,
            )
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid GHCNh station metadata at line $lineNumber: ${error.message}",
                error,
            )
        }
    }

    private fun String.field(
        startInclusive: Int,
        endExclusive: Int,
    ): String {
        if (length <= startInclusive) return ""
        return substring(startInclusive, minOf(length, endExclusive)).trim()
    }

    private fun String.requiredFiniteDouble(
        startInclusive: Int,
        endExclusive: Int,
        label: String,
        lineNumber: Int,
    ): Double {
        val raw = field(startInclusive, endExclusive)
        val value = raw.toDoubleOrNull()
            ?: throw IllegalArgumentException(
                "GHCNh station list line $lineNumber has invalid $label: $raw",
            )
        require(value.isFinite()) {
            "GHCNh station list line $lineNumber has non-finite $label"
        }
        return value
    }
}
