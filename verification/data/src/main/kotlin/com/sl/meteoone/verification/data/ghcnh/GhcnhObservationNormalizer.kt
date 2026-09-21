package com.sl.meteoone.verification.data.ghcnh

import com.sl.meteoone.verification.domain.ObservationStation
import com.sl.meteoone.verification.domain.PrecipitationObservation
import com.sl.meteoone.verification.domain.SurfaceObservation
import java.time.Instant

internal object GhcnhObservationNormalizer {
    fun normalize(
        candidate: GhcnhStationCandidate,
        observations: List<GhcnhRawObservation>,
        startInclusive: Instant,
        endExclusive: Instant,
    ): GhcnhObservationSeries {
        require(startInclusive.isBefore(endExclusive)) {
            "GHCNh observation window start must precede end"
        }

        val stationMetadata = candidate.station
        val station = ObservationStation(
            sourceId = GHCNH_SOURCE_ID,
            stationId = stationMetadata.stationId,
            latitude = stationMetadata.latitude,
            longitude = stationMetadata.longitude,
            elevationMeters = stationMetadata.elevationMeters,
        )

        val evidence = observations.asSequence()
            .filter { row ->
                row.stationId == stationMetadata.stationId &&
                    !row.observedAt.isBefore(startInclusive) &&
                    row.observedAt.isBefore(endExclusive)
            }
            .sortedBy { it.observedAt }
            .toList()

        val surface = ArrayList<SurfaceObservation>()
        val precipitation = ArrayList<PrecipitationObservation>()
        evidence.groupBy { it.observedAt }
            .toSortedMap()
            .forEach { (observedAt, duplicates) ->
                resolveSurface(station, observedAt, duplicates)?.let(surface::add)
                resolvePrecipitation(station, observedAt, duplicates)
                    .forEach(precipitation::add)
            }

        return GhcnhObservationSeries(
            stationMetadata = stationMetadata,
            station = station,
            surfaceObservations = surface,
            precipitationObservations = precipitation.sortedWith(PRECIPITATION_ORDER),
            evidence = evidence,
        )
    }

    private fun resolveSurface(
        station: ObservationStation,
        observedAt: Instant,
        rows: List<GhcnhRawObservation>,
    ): SurfaceObservation? {
        val temperature = resolveValue(rows, GhcnhVariable.TEMPERATURE) { it.isFinite() }
        val pressure = resolveValue(rows, GhcnhVariable.SEA_LEVEL_PRESSURE) {
            it.isFinite() && it > 0.0
        }
        val windSpeed = resolveValue(rows, GhcnhVariable.WIND_SPEED) {
            it.isFinite() && it >= 0.0
        }
        var windDirection = resolveValue(rows, GhcnhVariable.WIND_DIRECTION) {
            it.isFinite() && it in 0.0..360.0
        }

        // GHCNh documents 000 as calm. Preserve calm as a zero vector without inventing direction.
        if (windSpeed == 0.0 || windDirection == 0.0) {
            windDirection = null
        }

        val usableWind = windSpeed != null && (windSpeed == 0.0 || windDirection != null)
        if (temperature == null && pressure == null && !usableWind) {
            return null
        }

        return SurfaceObservation(
            station = station,
            observedAt = observedAt,
            temperatureC = temperature,
            pressureSeaLevelHpa = pressure,
            windSpeedMps = windSpeed,
            windDirectionDegrees = windDirection,
        )
    }

    private fun resolvePrecipitation(
        station: ObservationStation,
        observedAt: Instant,
        rows: List<GhcnhRawObservation>,
    ): List<PrecipitationObservation> =
        GhcnhVariable.entries.mapNotNull { variable ->
            val duration = variable.precipitationDuration ?: return@mapNotNull null
            val amount = resolveValue(rows, variable) { value ->
                value.isFinite() && value >= 0.0
            } ?: return@mapNotNull null
            PrecipitationObservation(
                station = station,
                interval = precipitationInterval(observedAt, duration),
                amountMm = amount,
            )
        }

    private fun resolveValue(
        rows: List<GhcnhRawObservation>,
        variable: GhcnhVariable,
        validValue: (Double) -> Boolean,
    ): Double? {
        val candidates = rows.mapNotNull { row ->
            val evidence = row.values[variable] ?: return@mapNotNull null
            val value = evidence.value ?: return@mapNotNull null
            if (!evidence.isExplicitlyUsable() || !validValue(value)) {
                return@mapNotNull null
            }
            if (
                variable.precipitationDuration != null &&
                evidence.attributes.measurementCode.equals("T", ignoreCase = true)
            ) {
                // Trace is real provenance, but converting it to 0 mm would fabricate an amount.
                return@mapNotNull null
            }
            RankedValue(
                value = value,
                rowCompleteness = rowCompleteness(row),
            )
        }
        if (candidates.isEmpty()) return null

        val bestCompleteness = candidates.maxOf { it.rowCompleteness }
        val bestValues = candidates.asSequence()
            .filter { it.rowCompleteness == bestCompleteness }
            .map { it.value }
            .distinct()
            .toList()
        return bestValues.singleOrNull()
    }

    private fun rowCompleteness(row: GhcnhRawObservation): Int =
        GhcnhVariable.entries.count { variable ->
            val evidence = row.values[variable] ?: return@count false
            val value = evidence.value ?: return@count false
            if (!evidence.isExplicitlyUsable()) return@count false
            if (
                variable.precipitationDuration != null &&
                evidence.attributes.measurementCode.equals("T", ignoreCase = true)
            ) {
                return@count false
            }
            when (variable) {
                GhcnhVariable.WIND_SPEED -> value >= 0.0
                GhcnhVariable.WIND_DIRECTION -> value in 0.0..360.0
                GhcnhVariable.PRECIPITATION,
                GhcnhVariable.PRECIPITATION_5_MINUTE,
                GhcnhVariable.PRECIPITATION_15_MINUTE,
                GhcnhVariable.PRECIPITATION_3_HOUR,
                GhcnhVariable.PRECIPITATION_6_HOUR,
                GhcnhVariable.PRECIPITATION_9_HOUR,
                GhcnhVariable.PRECIPITATION_12_HOUR,
                GhcnhVariable.PRECIPITATION_15_HOUR,
                GhcnhVariable.PRECIPITATION_18_HOUR,
                GhcnhVariable.PRECIPITATION_21_HOUR,
                GhcnhVariable.PRECIPITATION_24_HOUR,
                -> value >= 0.0

                else -> true
            }
        }

    private data class RankedValue(
        val value: Double,
        val rowCompleteness: Int,
    )
}
